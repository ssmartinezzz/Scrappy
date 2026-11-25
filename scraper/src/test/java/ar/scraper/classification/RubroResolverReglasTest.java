package ar.scraper.classification;

import ar.scraper.classification.RubroCatalogo.CategoriaRubro;
import ar.scraper.classification.RubroCatalogo.Rubro;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Clasificación")
@Feature("Rubro por tablas")
@DisplayName("RubroResolver: the rules of the taxonomy tables")
class RubroResolverReglasTest {

    private static final List<Rubro> RUBROS = List.of(
            new Rubro("indumentaria", "Indumentaria", 1, "IPC", 0, 3),
            new Rubro("tecnologia", "Tecnología", 2, "USD_OFICIAL", 2, 0),
            new Rubro("oficina", "Oficina", 3, "IPC", 1, 0),
            new Rubro("hogar", "Hogar", 4, "IPC", 0, 1));

    private static SiteRegistry sitio(String forzado, String porDefecto) {
        return SiteRegistry.forTesting(Map.of("s", new SiteRegistry.Sitio(
                "s", "s", "tiendanube", false, forzado, "config", false, porDefecto)));
    }

    private static RubroResolver resolver(SiteRegistry registry, List<Rubro> rubros, List<CategoriaRubro> filas) {
        return new RubroResolver(registry, new RubroCatalogo(rubros, filas, Map.of()));
    }

    private static final List<CategoriaRubro> FILAS = List.of(
            new CategoriaRubro("Remera", "", "indumentaria", false),
            new CategoriaRubro("Mesa", "", "hogar", false),
            new CategoriaRubro("Silla", "", "oficina", false),
            new CategoriaRubro("Silla", "comedor", "hogar", false));

    @Test
    @DisplayName("a forced site keeps the product when its weight is >= the category rubro's weight, loses when lower")
    void forcedSiteAgainstCategoryWeight() {
        RubroResolver tecnologia = resolver(sitio("tecnologia", null), RUBROS, FILAS);
        RubroResolver oficina = resolver(sitio("oficina", null), RUBROS, FILAS);

        assertThat(tecnologia.resolver("s", "Mesa", "")).isEqualTo("tecnologia");
        assertThat(tecnologia.resolver("s", "Remera", "")).isEqualTo("indumentaria");
        assertThat(oficina.resolver("s", "Mesa", "")).as("1 >= 1").isEqualTo("oficina");
        assertThat(oficina.resolver("s", "Remera", "")).as("1 < 3").isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("a forced rubro that is not in the taxonomy weighs 0: a category with weight > 0 beats it")
    void unknownForcedRubroWeighsZero() {
        RubroResolver desconocido = resolver(sitio("rubro-inexistente", null), RUBROS, FILAS);

        assertThat(desconocido.resolver("s", "Mesa", "")).isEqualTo("hogar");
        assertThat(desconocido.resolver("s", "Silla", "")).as("0 >= 0, no category weight").isEqualTo("rubro-inexistente");
    }

    @Test
    @DisplayName("a category with pisa_forzado beats a forced site whatever the weights, and the sub row is the most specific")
    void pisaForzado() {
        List<CategoriaRubro> filas = List.of(
                new CategoriaRubro("Silla", "", "oficina", false),
                new CategoriaRubro("Silla", "motorizada", "oficina", true),
                new CategoriaRubro("Pad", "", "oficina", true));
        RubroResolver tecnologia = resolver(sitio("tecnologia", null), RUBROS, filas);

        assertThat(tecnologia.resolver("s", "Pad", "")).isEqualTo("oficina");
        assertThat(tecnologia.resolver("s", "Silla", "", "motorizada")).isEqualTo("oficina");
        assertThat(tecnologia.resolver("s", "Silla", "", "otra")).as("no sub row: the category row does not pisa").isEqualTo("tecnologia");
        assertThat(tecnologia.resolver("s", "Silla", "")).isEqualTo("tecnologia");
    }

    @Test
    @DisplayName("a sub row is ignored on a forced site and used on a free one")
    void subRowOnlyOnFreeSites() {
        assertThat(resolver(sitio("tecnologia", null), RUBROS, FILAS).resolver("s", "Silla", "", "comedor")).isEqualTo("tecnologia");
        assertThat(resolver(sitio("  ", null), RUBROS, FILAS).resolver("s", "Silla", "", "comedor")).isEqualTo("hogar");
        assertThat(resolver(sitio(null, null), RUBROS, FILAS).resolver("s", "Silla", "", "comedor")).isEqualTo("hogar");
    }

    @Test
    @DisplayName("a category without a row falls to the site default, then the stored rubro, then indumentaria")
    void fallbacks() {
        assertThat(resolver(sitio(null, "hogar"), RUBROS, FILAS).resolver("s", "Otros", "oficina")).isEqualTo("hogar");
        assertThat(resolver(sitio(null, null), RUBROS, FILAS).resolver("s", "Otros", "oficina")).isEqualTo("oficina");
        assertThat(resolver(sitio(null, null), RUBROS, FILAS).resolver("s", null, " ")).isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("an empty taxonomy is refused instead of reclassifying everything")
    void emptyTaxonomyFails() {
        assertThatThrownBy(() -> new RubroCatalogo(List.of(), FILAS, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RubroCatalogo(RUBROS, List.of(), Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
