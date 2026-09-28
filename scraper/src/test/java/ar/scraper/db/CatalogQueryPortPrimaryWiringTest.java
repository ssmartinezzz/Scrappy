package ar.scraper.db;

import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `catalog-facets-perf`, T3 — with TWO beans implementing {@link CatalogQueryPort}
 * ({@link CatalogQueryRepository} and {@link CachingCatalogQueryPort}), Spring
 * needs {@code @Primary} to resolve the ambiguity; this proves it actually
 * does, against a real context instead of trusting the annotation by reading.
 *
 * <p>Same narrow-manual-context style as {@code SiteRegistrySingletonWiringTest}
 * (deliberately not {@code @SpringBootTest}).</p>
 */
@Epic("Configuration")
@Feature("Dependency injection")
@Story("El decorator de cache gana la ambigüedad de CatalogQueryPort")
@DisplayName("CatalogQueryPort — @Primary resuelve a CachingCatalogQueryPort, no a las dos")
class CatalogQueryPortPrimaryWiringTest extends PostgresTestBase {

    @Test
    @DisplayName("context.getBean(CatalogQueryPort.class) es el decorator de cache")
    void catalogQueryPortEsElCache() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("dataSource", dataSource());
            context.register(SiteRegistry.class, CatalogQueryRepository.class, CachingCatalogQueryPort.class);
            context.refresh();

            CatalogQueryPort port = context.getBean(CatalogQueryPort.class);

            assertThat(port).isInstanceOf(CachingCatalogQueryPort.class);
        }
    }
}
