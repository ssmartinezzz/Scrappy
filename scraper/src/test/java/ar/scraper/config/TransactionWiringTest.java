package ar.scraper.config;

import ar.scraper.db.support.PostgresTestBase;
import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Configuration")
@Feature("Transactions")
@Story("Repositories join Spring transactions")
@DisplayName("TransactionConfig — pool, proxy and rollback")
class TransactionWiringTest extends PostgresTestBase {

    /** Same shape as a repository: takes the DataSource, opens its own connection. */
    static class Probe {
        private final DataSource dataSource;

        Probe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Transactional(rollbackFor = Exception.class)
        public void insertTwo(boolean failBetween) throws SQLException {
            insert(1);
            if (failBetween) throw new SQLException("boom");
            insert(2);
        }

        public void insertWithoutBoundary() throws SQLException {
            insert(9);
        }

        private void insert(int id) throws SQLException {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO tx_wiring_probe VALUES (" + id + ")");
            }
        }
    }

    @Configuration
    @Import(TransactionConfig.class)
    static class Ctx {
        @Bean
        Probe probe(DataSource dataSource) {
            return new Probe(dataSource);
        }
    }

    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS tx_wiring_probe (id int)");
            st.execute("TRUNCATE tx_wiring_probe");
        }
        SimpleDriverDataSource simple = (SimpleDriverDataSource) dataSource();
        runner = new ApplicationContextRunner()
                .withUserConfiguration(Ctx.class)
                .withPropertyValues(
                        "spring.datasource.url=" + simple.getUrl(),
                        "spring.datasource.username=" + simple.getUsername(),
                        "spring.datasource.password=" + simple.getPassword(),
                        "spring.datasource.hikari.maximum-pool-size=7",
                        "spring.datasource.hikari.connection-timeout=4000");
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tx_wiring_probe");
        }
    }

    private int rows() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM tx_wiring_probe")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("the pool keeps the spring.datasource.hikari.* binding")
    void poolBindsHikariProperties() {
        runner.run(ctx -> {
            HikariDataSource pool = ctx.getBean("hikariPool", HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(7);
            assertThat(pool.getConnectionTimeout()).isEqualTo(4000);
        });
    }

    @Test
    @DisplayName("repositories get the transaction-aware proxy; Flyway and the manager get the raw pool")
    void primaryDataSourceIsTheProxy() throws Exception {
        runner.run(ctx -> {
            assertThat(ctx.getBean(DataSource.class)).isInstanceOf(TransactionAwareDataSourceProxy.class);
            assertThat(TransactionConfig.class.getDeclaredMethod("hikariPool",
                    org.springframework.boot.autoconfigure.jdbc.DataSourceProperties.class)
                    .isAnnotationPresent(FlywayDataSource.class)).isTrue();
        });
    }

    @Test
    @DisplayName("a failure between two writes rolls both back")
    void failureRollsBackTheWholeUnit() {
        runner.run(ctx -> {
            Probe probe = ctx.getBean(Probe.class);
            assertThat(AopUtils.isAopProxy(probe)).isTrue();
            assertThatThrownBy(() -> probe.insertTwo(true)).isInstanceOf(SQLException.class);
            assertThat(rows()).isZero();
            probe.insertTwo(false);
            assertThat(rows()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("outside a transaction the proxy behaves like the pool: the write commits on its own")
    void withoutBoundaryTheWriteAutocommits() {
        runner.run(ctx -> {
            ctx.getBean(Probe.class).insertWithoutBoundary();
            assertThat(rows()).isEqualTo(1);
        });
    }
}
