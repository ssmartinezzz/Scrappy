package ar.scraper.db;

import ar.scraper.aggregator.normalize.SiteRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 1 follow-up (coordinator
 * review, apply session 3). {@link DatabaseService} carries two constructors:
 * a 1-arg backward-compatible overload (for the ~46 existing test call
 * sites, each building its own private {@link SiteRegistry}) and the
 * {@code @Autowired} 2-arg constructor Spring actually uses in production.
 * The risk that overload exists to avoid — churning those 46 call sites —
 * is exactly the risk this test closes: if Spring ever resolved the WRONG
 * constructor, or if the 2-arg constructor stopped being the one marked
 * {@code @Autowired}, production would silently end up with two live
 * {@code SiteRegistry} instances (one for {@code DatabaseService} and
 * everything that reads it via {@code db.siteRegistry()}, another for
 * anything Spring injects {@link SiteRegistry} into directly) that never
 * see each other's {@code reload()} calls.
 *
 * <p>Deliberately NOT {@code @SpringBootTest} (see {@code SpringWiringTest}'s
 * doc comment for why this codebase avoids booting the full application in
 * a test — browser, network, Flyway side effects). This registers only the
 * two beans under test plus a {@link DataSource} stub that fails every
 * connection attempt on purpose: {@link SiteRegistry#reload} and
 * {@link DatabaseService}'s {@code @PostConstruct} both swallow that
 * failure (same "infra absence never fails the suite" posture as
 * {@code TEST-3}), so no real database is needed to prove the wiring.</p>
 */
@Epic("Configuration")
@Feature("Dependency injection")
@Story("SiteRegistry is one Spring singleton, not two")
@DisplayName("DatabaseService + SiteRegistry — production wiring shares one instance")
class SiteRegistrySingletonWiringTest {

    @Configuration
    static class UnreachableDataSourceConfig {
        @Bean
        DataSource dataSource() {
            return new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLException("no DB in this wiring-only test, on purpose");
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    return getConnection();
                }

                @Override
                public java.io.PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(java.io.PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public <T> T unwrap(Class<T> iface) {
                    return null;
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };
        }
    }

    @Test
    @DisplayName("context.getBean(SiteRegistry.class) es exactamente db.siteRegistry()")
    void databaseServiceSiteRegistryIsTheSameSpringSingleton() {
        try (var context = new AnnotationConfigApplicationContext(
                UnreachableDataSourceConfig.class, SiteRegistry.class, DatabaseService.class)) {

            SiteRegistry theSingleton = context.getBean(SiteRegistry.class);
            DatabaseService db = context.getBean(DatabaseService.class);

            assertThat(db.siteRegistry())
                    .as("DatabaseService must be wired through the @Autowired 2-arg constructor, "
                            + "not the 1-arg backward-compat overload that builds its own SiteRegistry")
                    .isSameAs(theSingleton);
        }
    }
}
