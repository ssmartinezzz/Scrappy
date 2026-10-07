package ar.scraper.config;

import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Configuration")
@Feature("Database boot")
@Story("The app waits for the database instead of dying on the first refusal")
@DisplayName("Hikari and Flyway retry settings")
class DatabaseBootRetryConfigTest {

    @Configuration
    @Import(TransactionConfig.class)
    static class Ctx {
    }

    private ApplicationContextRunner runner(String... extra) {
        String[] base = {
                "DATABASE_URL=jdbc:postgresql://127.0.0.1:1/none",
                "DATABASE_USERNAME=u",
                "DATABASE_PASSWORD=p",
                "APP_CORS_ALLOWED_ORIGINS=http://localhost",
        };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Ctx.class)
                .withPropertyValues(all);
    }

    @Test
    @DisplayName("defaults: the pool waits 60 s for the first connection, Flyway retries 10 times")
    void defaultsKeepRetrying() {
        runner().run(ctx -> {
            HikariDataSource pool = ctx.getBean("hikariPool", HikariDataSource.class);
            assertThat(pool.getInitializationFailTimeout()).isEqualTo(60_000L);
            assertThat(pool.getConnectionTimeout()).isEqualTo(30_000L);
            FlywayProperties flyway = Binder.get(ctx.getEnvironment())
                    .bind("spring.flyway", FlywayProperties.class).get();
            assertThat(flyway.getConnectRetries()).isEqualTo(10);
            assertThat(flyway.getConnectRetriesInterval()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    @DisplayName("the optional env vars override every setting on the explicit pool bean")
    void envOverrides() {
        runner("DB_INIT_FAIL_TIMEOUT_MS=1234", "DB_CONNECTION_TIMEOUT_MS=4321",
                "DB_CONNECT_RETRIES=3", "DB_CONNECT_RETRY_INTERVAL=2s").run(ctx -> {
            HikariDataSource pool = ctx.getBean("hikariPool", HikariDataSource.class);
            assertThat(pool.getInitializationFailTimeout()).isEqualTo(1234L);
            assertThat(pool.getConnectionTimeout()).isEqualTo(4321L);
            FlywayProperties flyway = Binder.get(ctx.getEnvironment())
                    .bind("spring.flyway", FlywayProperties.class).get();
            assertThat(flyway.getConnectRetries()).isEqualTo(3);
            assertThat(flyway.getConnectRetriesInterval()).isEqualTo(Duration.ofSeconds(2));
        });
    }
}
