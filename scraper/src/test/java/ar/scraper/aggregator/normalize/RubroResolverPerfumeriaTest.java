package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Rubro")
@Story("Perfume is its own rubro")
@DisplayName("RubroResolver — Perfume is perfumeria, except on a site forced to tecnologia or oficina")
class RubroResolverPerfumeriaTest {

    private static RubroResolver resolver() {
        Map<String, SiteRegistry.Sitio> cache = new LinkedHashMap<>();
        cache.put("moda", new SiteRegistry.Sitio("Moda", "moda", "vtex", false, null, "config"));
        cache.put("easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "config", false, "construccion"));
        cache.put("juguetes", new SiteRegistry.Sitio("Juguetes", "juguetes", "tiendanube", false, null, "config", false, "juguetes"));
        cache.put("tech", new SiteRegistry.Sitio("Tech", "tech", "tiendanube", false, "tecnologia", "config"));
        cache.put("office", new SiteRegistry.Sitio("Office", "office", "tiendanube", false, "oficina", "config"));
        return new RubroResolver(SiteRegistry.forTesting(cache));
    }

    @Test
    @DisplayName("Perfume is in the perfumeria group and still in the canonical vocabulary")
    void inTheGroup() {
        assertThat(CategoryGroups.esCategoriaPerfumeria("Perfume")).isTrue();
        assertThat(CategoryGroups.esCategoriaPerfumeria("Remera")).isFalse();
        assertThat(CategoryGroups.esCategoriaPerfumeria(null)).isFalse();
        assertThat(CategoryGroups.canonicalCategories()).contains("Perfume");
    }

    @ParameterizedTest(name = "[{0}] Perfume -> {1}")
    @CsvSource({
            "moda, perfumeria",
            "easy, perfumeria",
            "juguetes, perfumeria",
            "tech, tecnologia",
            "office, oficina"
    })
    @DisplayName("a perfume from a mixed store is perfumeria; a forced tecnologia/oficina site keeps its rubro")
    void perfumePerSite(String sitio, String esperado) {
        assertThat(resolver().resolver(sitio, "Perfume", null)).isEqualTo(esperado);
        assertThat(resolver().resolver(sitio, "Perfume", "indumentaria")).isEqualTo(esperado);
    }

    @Test
    @DisplayName("other categories are untouched by the new branch")
    void othersUntouched() {
        assertThat(resolver().resolver("moda", "Remera", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("easy", "Otros", null)).isEqualTo("construccion");
    }
}
