package ar.scraper.aggregator.normalize;

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
@Story("Rubro of the categories added for Easy on sites without a forced rubro")
@DisplayName("RubroResolver — new categories on unforced sites, site default and forced rubros")
class RubroResolverEasyCategoriesTest {

    private static RubroResolver resolver() {
        Map<String, SiteRegistry.Sitio> cache = new LinkedHashMap<>();
        cache.put("easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "config", false, "construccion"));
        cache.put("sporting", new SiteRegistry.Sitio("Sporting", "sporting", "vtex", false, null, "config"));
        cache.put("inpro", new SiteRegistry.Sitio("Inpro", "inpro", "inpro", false, "oficina", "config"));
        cache.put("compragamer", new SiteRegistry.Sitio("Compragamer", "compragamer", "custom", false, "tecnologia", "config"));
        cache.put("flowin", new SiteRegistry.Sitio("Flowin", "flowin", "tiendanube", false, "hogar", "config"));
        cache.put("mundoconstruccion", new SiteRegistry.Sitio("Mundo", "mundoconstruccion", "tiendanube", false, "construccion", "config"));
        return new RubroResolver(SiteRegistry.forTesting(cache));
    }

    @ParameterizedTest(name = "[easy] {0} -> {1}")
    @CsvSource({
            "Heladera, hogar",
            "Parrilla, hogar",
            "Lubricante, hogar",
            "Carpa, hogar",
            "Termotanque, hogar",
            "Maceta, hogar",
            "Sanitario, hogar",
            "Cemento, construccion",
            "Taladro, construccion",
            "Iluminación, construccion",
            "Cable Eléctrico, construccion"
    })
    @DisplayName("hogar and construccion categories keep their rubro on an unforced site")
    void hogarAndConstruccionCategories(String categoria, String esperado) {
        assertThat(resolver().resolver("easy", categoria, null)).isEqualTo(esperado);
        assertThat(resolver().resolver("easy", categoria, "indumentaria")).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[easy] {0} -> tecnologia")
    @CsvSource({"TV y Audio", "Monitor", "Cable", "Auricular", "Reloj", "Notebook"})
    @DisplayName("every tech category on an unforced site is tecnologia and beats the builder default")
    void techCategoriesAreTecnologia(String categoria) {
        assertThat(resolver().resolver("easy", categoria, null)).isEqualTo("tecnologia");
        assertThat(resolver().resolver("easy", categoria, "indumentaria")).isEqualTo("tecnologia");
        assertThat(resolver().resolver("sporting", categoria, "indumentaria")).isEqualTo("tecnologia");
    }

    @ParameterizedTest(name = "{0} -> oficina")
    @CsvSource({"Silla", "Escritorio", "Organización"})
    @DisplayName("office categories on an unforced site are oficina and beat the builder default")
    void officeCategoriesAreOficina(String categoria) {
        assertThat(resolver().resolver("easy", categoria, null)).isEqualTo("oficina");
        assertThat(resolver().resolver("easy", categoria, "indumentaria")).isEqualTo("oficina");
        assertThat(resolver().resolver("sporting", categoria, "indumentaria")).isEqualTo("oficina");
    }

    @ParameterizedTest(name = "{0} -> deporte")
    @CsvSource({"Bicicleta", "Monopatín", "Repuesto de Bicicleta", "Pesas", "Accesorio de Fitness", "Kayak y Bote",
            "Equipamiento Deportivo", "Pelota", "Paleta"})
    @DisplayName("sports equipment categories on an unforced site are deporte and beat the builder default")
    void sportsEquipmentIsDeporte(String categoria) {
        assertThat(resolver().resolver("easy", categoria, "indumentaria")).isEqualTo("deporte");
        assertThat(resolver().resolver("sporting", categoria, "indumentaria")).isEqualTo("deporte");
        assertThat(resolver().resolver("sporting", categoria, null)).isEqualTo("deporte");
    }

    @ParameterizedTest(name = "{0} -> juguetes")
    @CsvSource({"Juguete", "Cama Elástica"})
    @DisplayName("toy categories on an unforced site are juguetes and beat the builder default")
    void toysAreJuguetes(String categoria) {
        assertThat(resolver().resolver("easy", categoria, "indumentaria")).isEqualTo("juguetes");
        assertThat(resolver().resolver("sporting", categoria, "indumentaria")).isEqualTo("juguetes");
    }

    @ParameterizedTest(name = "{0} stays indumentaria")
    @CsvSource({"Remera", "Zapatilla Running", "Short"})
    @DisplayName("sports clothing and footwear stay indumentaria, the deporte rubro is for equipment")
    void sportsClothingStaysIndumentaria(String categoria) {
        assertThat(resolver().resolver("sporting", categoria, "indumentaria")).isEqualTo("indumentaria");
        assertThat(resolver().resolver("easy", categoria, null)).isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("rubro_por_defecto decides a name that reached the tail and beats the builder default")
    void siteDefaultBeatsTheExistingRubro() {
        assertThat(resolver().resolver("easy", "Otros", "indumentaria")).isEqualTo("construccion");
        assertThat(resolver().resolver("easy", "Otros", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("easy", "", "indumentaria")).isEqualTo("construccion");
        assertThat(resolver().resolver("easy", "Alimentos", "indumentaria")).isEqualTo("suplementos");
    }

    @Test
    @DisplayName("rubro_por_defecto never overrides a category that already has a rubro")
    void siteDefaultOnlyAppliesInTheTail() {
        assertThat(resolver().resolver("easy", "Pesas", null)).isEqualTo("deporte");
        assertThat(resolver().resolver("easy", "Juguete", null)).isEqualTo("juguetes");
        assertThat(resolver().resolver("easy", "Monitor", null)).isEqualTo("tecnologia");
        assertThat(resolver().resolver("easy", "Silla", null)).isEqualTo("oficina");
        assertThat(resolver().resolver("easy", "Calzado", null)).isEqualTo("construccion");
        assertThat(resolver().resolver("easy", "Zapatilla Running", null)).isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("a site without default keeps the existing rubro, and indumentaria when there is none")
    void siteWithoutDefaultFallsBackToTheExistingRubro() {
        assertThat(resolver().resolver("sporting", "Otros", "hogar")).isEqualTo("hogar");
        assertThat(resolver().resolver("sporting", "Otros", null)).isEqualTo("indumentaria");
        assertThat(resolver().resolver("noexiste", "Otros", null)).isEqualTo("indumentaria");
    }

    @ParameterizedTest(name = "[{0}] {1} -> {2}")
    @CsvSource({
            "compragamer, Bicicleta, tecnologia",
            "compragamer, Juguete, tecnologia",
            "compragamer, Taladro, tecnologia",
            "inpro, Bicicleta, oficina",
            "inpro, Juguete, oficina",
            "inpro, Taladro, oficina",
            "flowin, Bicicleta, hogar",
            "flowin, Juguete, hogar",
            "flowin, Otros, hogar",
            "mundoconstruccion, Bicicleta, construccion",
            "mundoconstruccion, Juguete, construccion"
    })
    @DisplayName("a forced rubro wins over the deporte, juguetes and default branches of unforced sites")
    void forcedRubroWins(String sitio, String categoria, String esperado) {
        assertThat(resolver().resolver(sitio, categoria, "indumentaria")).isEqualTo(esperado);
    }

    @Test
    @DisplayName("a hogar category still beats a forced construccion rubro, as it always did")
    void hogarCategoryBeatsForcedConstruccion() {
        assertThat(resolver().resolver("mundoconstruccion", "Heladera", "indumentaria")).isEqualTo("hogar");
    }
}
