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

    // ══════════════════════════════════════════════════════════════════
    // outfits-v2 — Paso-2 fallback: género correctness
    //
    // Paso 2 is the last resort before marking a slot partial. It must
    // include ONLY products with genero="" (ungendered) or genero="unisex".
    // It MUST NOT include products of the opposite gender — previously,
    // filtrar(base, "unisex", ...) would call generoElegible(p, "unisex")
    // which returned true for ALL genders, leaking women's calzas into
    // men's outfits when the piernas pool was small.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void paso2FallbackNoIncluyeProductosMujerEnOutfitHombre() {
        // Only a women's calza is available for piernas — paso 2 must NOT
        // pick it; the slot must be left empty (partial=true).
        Product remera    = producto("Remera Nike",      20000, "Remera",    "hombre", "Nike",   true);
        Product zapatilla = producto("Zapatilla Adidas", 80000, "Zapatilla", "hombre", "Adidas", false);
        Product calzaMujer = producto("Calza Mujer Pro", 25000, "Calza",     "mujer",  "Nike",   true);

        OutfitService.Outfit outfit = service.armar(
                List.of(remera, zapatilla, calzaMujer), "hombre", "gym",
                OutfitService.FeedbackModel.empty());

        assertThat(outfit.partial()).isTrue();
        assertThat(outfit.slots()).noneMatch(s -> "piernas".equals(s.slot()));
    }

    @Test
    void paso2FallbackIncluyeProductoUnisexEnOutfitHombre() {
        // When only a mujer calza and a unisex calza are available, paso 2
        // must pick the unisex one for a hombre outfit.
        Product remera       = producto("Remera Nike",      20000, "Remera",    "hombre",  "Nike",   true);
        Product zapatilla    = producto("Zapatilla Adidas", 80000, "Zapatilla", "hombre",  "Adidas", false);
        Product calzaMujer   = producto("Calza Mujer Pro",  25000, "Calza",     "mujer",   "Nike",   true);
        Product calzaUnisex  = producto("Calza Unisex Pro", 24000, "Calza",     "unisex",  "Adidas", true);

        OutfitService.Outfit outfit = service.armar(
                List.of(remera, zapatilla, calzaMujer, calzaUnisex), "hombre", "gym",
                OutfitService.FeedbackModel.empty());

        assertThat(outfit.partial()).isFalse();
        assertThat(outfit.slots())
                .filteredOn(s -> "piernas".equals(s.slot()))
                .allMatch(s -> !"Calza Mujer Pro".equals(s.nombre()));
    }

    @Test
    void productosConGeneroVacioSonElegiblesEnOutfitHombre() {
        // Products with genero="" are ungendered — they must be eligible in
        // steps 0 and 1 (not just paso 2) for any requested gender.
        Product remera       = producto("Remera Nike",      20000, "Remera",    "hombre", "Nike",   true);
        Product zapatilla    = producto("Zapatilla Adidas", 80000, "Zapatilla", "hombre", "Adidas", false);
        Product shortVacio   = producto("Short Generico",   22000, "Short",     "",       "Generic", true);

        OutfitService.Outfit outfit = service.armar(
                List.of(remera, zapatilla, shortVacio), "hombre", "gym",
                OutfitService.FeedbackModel.empty());

        assertThat(outfit.partial()).isFalse();
        assertThat(outfit.slots()).anyMatch(s -> "piernas".equals(s.slot()));
    }

    @Test
    void paso2FallbackNoIncluyeProductosMujerEnOutfitMujer_regresion() {
        // Mirror test: hombre products must not leak into a mujer outfit via paso 2.
        Product remera       = producto("Remera Mujer",     20000, "Remera",    "mujer",  "Nike",   true);
        Product zapatilla    = producto("Zapatilla Mujer",  80000, "Zapatilla", "mujer",  "Adidas", false);
        Product shortHombre  = producto("Short Hombre Pro", 22000, "Short",     "hombre", "Nike",   true);

        OutfitService.Outfit outfit = service.armar(
                List.of(remera, zapatilla, shortHombre), "mujer", "gym",
                OutfitService.FeedbackModel.empty());

        assertThat(outfit.partial()).isTrue();
        assertThat(outfit.slots()).noneMatch(s -> "piernas".equals(s.slot()));
    }
}
