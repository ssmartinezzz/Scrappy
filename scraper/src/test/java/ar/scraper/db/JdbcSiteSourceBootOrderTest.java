package ar.scraper.db;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SiteRegistry reads {@code sitio} once, at construction. Built before Flyway, it misses the rows of a
 * pending seed migration and routes those sites to the tiendanube abstention: 0 products, no error,
 * on the first run after every deploy that adds a site (measured with V43, 2026-10-08).
 */
@Epic("Configuration")
@Feature("Database boot")
@Story("The site registry loads after Flyway migrates")
@DisplayName("JdbcSiteSource waits for the Flyway initializer")
class JdbcSiteSourceBootOrderTest {

    @Configuration
    @Import({DatabaseInitializationDependencyConfigurer.class, JdbcSiteSource.class})
    static class Ctx {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        FlywayMigrationInitializer flywayInitializer() {
            return new FlywayMigrationInitializer(mock(Flyway.class));
        }
    }

    @Test
    @DisplayName("jdbcSiteSource depends on flywayInitializer")
    void dependsOnFlyway() {
        new ApplicationContextRunner().withUserConfiguration(Ctx.class).run(ctx -> {
            String name = ctx.getBeanNamesForType(JdbcSiteSource.class)[0];
            assertThat(ctx.getBeanFactory().getDependenciesForBean(name)).contains("flywayInitializer");
        });
    }
}
