package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Regenerar" did nothing.
 *
 * <p>The button re-sent an identical request, and since the pick ranking was made
 * fully deterministic (marca → price-per-unit → baseMlScore → url) to stop the
 * builder returning a different supplement on every request, the server answered
 * byte-identically. The fix for one complaint created the other.</p>
 *
 * <p>Re-introducing randomness would just restore the first bug. Instead the caller
 * says what it has already been shown, and the builder offers the next best —
 * deterministic, and it actually progresses. Same shape as the outfit builder's
 * slot-swap {@code excluirUrls}.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Supplement combo")
@Story("Regenerate")
@DisplayName("SupplementCombo — regenerating offers the next candidate")
class SupplementComboExcluirTest {

    private final SupplementCombo combo = new SupplementCombo(new RecommendationService());

    private Product whey(String marca, String nombre, double precio) {
        return new Product("entreno", nombre, precio, null,
                "https://test/" + Math.abs(nombre.hashCode()), "https://img/x.jpg",
                "Proteína", "unisex", List.of(), Product.MlScore.EMPTY, marca, "suplementos", false);
    }

    private final Product a = whey("ENA",  "Whey Protein ENA 1kg",      50000);
    private final Product b = whey("STAR", "Whey Protein STAR 1kg",     45000);
    private final Product c = whey("Otra", "Whey Protein Generica 1kg", 40000);
    private final List<Product> catalogo = List.of(a, b, c);
    private final Set<String> soloPolvo = Set.of("Proteína en Polvo");

    private String urlElegida(Set<String> excluir) {
        List<OutfitService.SupplementPick> picks =
                combo.armarComboSuplementos(catalogo, 0, soloPolvo, excluir);
        return picks.isEmpty() ? null : picks.get(0).url();
    }

    @Test
    @DisplayName("without exclusions the pick is still stable across calls")
    void sinExclusionesElPickSigueSiendoEstable() {
        // The determinism that broke regenerate is deliberate and must survive:
        // two identical requests still answer identically.
        assertThat(urlElegida(Set.of())).isEqualTo(urlElegida(Set.of()));
    }

    @Test
    @DisplayName("excluding what was shown yields a different product")
    void excluirLoMostradoDevuelveOtroProducto() {
        String primero = urlElegida(Set.of());
        String segundo = urlElegida(Set.of(primero));

        assertThat(segundo).isNotNull().isNotEqualTo(primero);
    }

    @Test
    @DisplayName("regenerating walks the whole pool before repeating")
    void regenerarRecorreTodoElPoolAntesDeRepetir() {
        String p1 = urlElegida(Set.of());
        String p2 = urlElegida(Set.of(p1));
        String p3 = urlElegida(Set.of(p1, p2));

        assertThat(List.of(p1, p2, p3)).doesNotHaveDuplicates().hasSize(3);
    }

    @Test
    @DisplayName("once everything is excluded it cycles instead of emptying the row")
    void alAgotarseElPoolVuelveAEmpezarEnVezDeVaciarse() {
        // A blank row is worse product than a repeat: the user asked for a stack of
        // supplements, not for the panel to shrink as they click.
        String todo = urlElegida(Set.of(a.url(), b.url(), c.url()));

        assertThat(todo).isNotNull();
        assertThat(List.of(a.url(), b.url(), c.url())).contains(todo);
    }

    @Test
    @DisplayName("exclusions on one subtype do not disturb another")
    void laExclusionDeUnSubtipoNoAfectaAOtro() {
        Product creatina = new Product("entreno", "Creatina Monohidrato ENA 300g", 30000, null,
                "https://test/creatina", "https://img/x.jpg", "Creatina", "unisex", List.of(),
                Product.MlScore.EMPTY, "ENA", "suplementos", false);

        List<Product> mixto = List.of(a, b, c, creatina);
        List<OutfitService.SupplementPick> picks = combo.armarComboSuplementos(
                mixto, 0, Set.of("Proteína en Polvo", "Creatina"), Set.of(a.url()));

        assertThat(picks).extracting(OutfitService.SupplementPick::tipo)
                .containsExactlyInAnyOrder("Proteína en Polvo", "Creatina");
        assertThat(picks).extracting(OutfitService.SupplementPick::url)
                .doesNotContain(a.url())
                .contains(creatina.url());
    }

    @Test
    @DisplayName("null exclusions behave exactly like the three-arg overload")
    void excluirNuloEsIdenticoAlOverloadPrevio() {
        assertThat(combo.armarComboSuplementos(catalogo, 0, soloPolvo, null))
                .usingRecursiveComparison()
                .isEqualTo(combo.armarComboSuplementos(catalogo, 0, soloPolvo));
    }
}
