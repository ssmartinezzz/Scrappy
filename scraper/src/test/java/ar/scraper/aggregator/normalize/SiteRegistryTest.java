package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 1 (design E1). Classpath-only
 * — {@link SiteRegistry#forTesting} seeds the cache directly, no DB.
 */
@Epic("Normalization")
@Feature("Site registry")
@Story("A miss abstains, never guesses")
@DisplayName("SiteRegistry — porKey (no DB)")
class SiteRegistryTest {

    @Test
    @DisplayName("un sitioKey desconocido abstiene: tiendanube/false/null")
    void unknownSitioKeyAbstains() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of());

        SiteRegistry.Sitio sitio = registry.porKey("unsitionoregistrado");

        assertThat(sitio.plataforma()).isEqualTo("tiendanube");
        assertThat(sitio.esPremium()).isFalse();
        assertThat(sitio.rubroForzado()).isNull();
    }

    @Test
    @DisplayName("los tres accesores delegan a porKey para un miss")
    void accessorsDelegateToPorKeyOnMiss() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of());

        assertThat(registry.plataforma("otronoregistrado")).isEqualTo("tiendanube");
        assertThat(registry.esPremium("otronoregistrado")).isFalse();
        assertThat(registry.rubroForzado("otronoregistrado")).isNull();
    }

    @Test
    @DisplayName("un sitioKey sembrado devuelve exactamente su fila")
    void seededSitioKeyReturnsItsRow() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "harvey", new SiteRegistry.Sitio("Harvey", "harvey", "tiendanube", true, null, "config"),
                "maximus", new SiteRegistry.Sitio("Maximus", "maximus", "maximus", false, "tecnologia", "config")
        ));

        assertThat(registry.esPremium("harvey")).isTrue();
        assertThat(registry.plataforma("harvey")).isEqualTo("tiendanube");
        assertThat(registry.rubroForzado("maximus")).isEqualTo("tecnologia");
        assertThat(registry.plataforma("maximus")).isEqualTo("maximus");
    }
}
