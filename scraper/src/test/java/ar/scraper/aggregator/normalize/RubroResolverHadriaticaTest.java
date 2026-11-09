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
@Story("Rubro of the gaming and collectible categories on a toys site")
@DisplayName("RubroResolver — Consola/Videojuego are tecnologia, the toy categories are juguetes")
class RubroResolverHadriaticaTest {

    private static RubroResolver resolver() {
        Map<String, SiteRegistry.Sitio> cache = new LinkedHashMap<>();
        cache.put("hadriatica", new SiteRegistry.Sitio("Hadriatica", "hadriatica", "tiendanube", false, null, "config", false, "juguetes"));
        cache.put("gamerfactory", new SiteRegistry.Sitio("GamerFactory", "gamerfactory", "tiendanube", false, "tecnologia", "config"));
        return new RubroResolver(SiteRegistry.forTesting(cache));
    }

    @Test
    @DisplayName("the five categories are in the canonical vocabulary")
    void inTheCanon() {
        assertThat(CategoryGroups.canonicalCategories())
                .contains("Videojuego", "Consola", "Figura Coleccionable", "Peluche", "Juego de Mesa", "Libro");
    }

    @ParameterizedTest(name = "[hadriatica] {0} -> {1}")
    @CsvSource({
            "Videojuego, tecnologia",
            "Consola, tecnologia",
            "Joystick, tecnologia",
            "Figura Coleccionable, juguetes",
            "Peluche, juguetes",
            "Juego de Mesa, juguetes",
            "Libro, libros",
            "Juguete, juguetes",
            "Otros, juguetes",
            "Medias, indumentaria"
    })
    @DisplayName("an unforced toys site sends each category to its rubro; Otros takes the site default")
    void rubroPerCategory(String categoria, String esperado) {
        assertThat(resolver().resolver("hadriatica", categoria, null)).isEqualTo(esperado);
        assertThat(resolver().resolver("hadriatica", categoria, "indumentaria")).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[gamerfactory] {0} -> tecnologia")
    @CsvSource({"Videojuego", "Consola"})
    @DisplayName("on a site forced to tecnologia the two gaming categories stay tecnologia")
    void forcedTechSite(String categoria) {
        assertThat(resolver().resolver("gamerfactory", categoria, null)).isEqualTo("tecnologia");
    }
}
