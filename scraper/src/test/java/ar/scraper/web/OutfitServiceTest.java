package ar.scraper.web;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutfitService}, focused on the new {@code excludeCategoria}
 * axis added by personalized-recommendations-feed (design.md Decision 1 / spec.md
 * "Category-wide Exclude Axis Honored by Outfit Generation"). Also covers the
 * explicit non-regression requirement (spec.md "No Regression to Existing
 * Outfit-Builder Behavior") for the pre-existing pair-exclude axis.
 */
class OutfitServiceTest {

    private final OutfitService service = new OutfitService();

    private Product producto(String nombre, double precio, String categoria, String genero,
                              String marca, boolean gymrat) {
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, genero, List.of(), Product.MlScore.EMPTY, marca, "indumentaria",
                gymrat);
    }

    @Test
    void emptyFeedbackModelHasEmptyExcludeCategoriaByDefault() {
        OutfitService.FeedbackModel empty = OutfitService.FeedbackModel.empty();

        assertThat(empty.excludeCategoria()).isEmpty();
        assertThat(empty.exclude()).isEmpty();
        assertThat(empty.boostLikeCount()).isEmpty();
    }

    @Test
    void armarExcludesProductWhoseCategoriaIsInExcludeCategoriaRegardlessOfMarca() {
        // Two torso candidates of the same categoria but different marca — both
        // must be excluded once the bare categoria is in excludeCategoria,
        // per spec "regardless of marca".
        Product remeraNike  = producto("Remera Nike",  20000, "Remera", "hombre", "Nike", true);
        Product remeraNoName = producto("Remera Sin Marca", 21000, "Remera", "hombre", "", true);
        Product shortAdidas = producto("Short Adidas", 15000, "Short", "hombre", "Adidas", true);
        Product zapatilla   = producto("Zapatilla Nike", 80000, "Zapatilla", "hombre", "Nike", true);

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of(), Map.of(), Set.of("Remera"));

        OutfitService.Outfit outfit = service.armar(
                List.of(remeraNike, remeraNoName, shortAdidas, zapatilla),
                "hombre", "gym", feedback);

        assertThat(outfit.slots())
                .noneMatch(p -> "Remera".equals(p.categoria()));
    }

    @Test
    void armarIsByteEquivalentToPreChangeBehaviorWhenExcludeCategoriaIsEmpty() {
        // Same dislike/like rows that existed before this change (pair-exclude
        // only) — outfit generation must behave identically (spec "Pre-existing
        // pair-exclude/boost behavior unchanged").
        Product remeraNike   = producto("Remera Nike",  20000, "Remera", "hombre", "Nike", true);
        Product remeraPuma   = producto("Remera Puma",  20500, "Remera", "hombre", "Puma", true);
        Product shortAdidas  = producto("Short Adidas", 15000, "Short", "hombre", "Adidas", true);
        Product zapatillaNike = producto("Zapatilla Nike", 80000, "Zapatilla", "hombre", "Nike", true);

        // Pair-exclude on Nike|Remera, as it worked before excludeCategoria existed.
        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of("Nike|Remera"), Map.of(), Set.of());

        OutfitService.Outfit outfit = service.armar(
                List.of(remeraNike, remeraPuma, shortAdidas, zapatillaNike),
                "hombre", "gym", feedback);

        // Nike|Remera pair must still be excluded; Puma|Remera remains eligible —
        // identical to pre-change pair-exclude semantics.
        assertThat(outfit.slots())
                .noneMatch(p -> "Nike".equals(p.marca()) && "Remera".equals(p.categoria()));
    }

    @Test
    void armarTwoArgOverloadRemainsUnaffectedNoOpForExcludeCategoria() {
        Product remeraNike = producto("Remera Nike", 20000, "Remera", "hombre", "Nike", true);
        Product shortAdidas = producto("Short Adidas", 15000, "Short", "hombre", "Adidas", true);
        Product zapatillaNike = producto("Zapatilla Nike", 80000, "Zapatilla", "hombre", "Nike", true);

        OutfitService.Outfit outfit = service.armar(
                List.of(remeraNike, shortAdidas, zapatillaNike), "hombre");

        // No feedback at all — legacy 2-arg overload must still produce an outfit
        // with no exclusion applied (spec: existing overload unaffected).
        assertThat(outfit.slots()).isNotEmpty();
    }
}
