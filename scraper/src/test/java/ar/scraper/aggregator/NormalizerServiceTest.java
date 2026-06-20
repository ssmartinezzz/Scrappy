package ar.scraper.aggregator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizerService#detectarCantidadUnidades(String, String)},
 * the pack/combo unit-count detector (PR 1 of pack-pricing-detection).
 *
 * Pattern priority (per design):
 *   1) explicit pack/combo/set/kit keyword + count
 *   2) N + garment-plural noun adjacency
 *   3) N piezas/prendas/unidades
 *   4) distinct-garment combo (torso+piernas heuristic) -> fixed 2
 * Ambiguous numeric tokens (SKU/model numbers, size ranges, color counts)
 * MUST fall back to 1 (conservative default).
 */
class NormalizerServiceTest {

    private final NormalizerService service = new NormalizerService();

    // ── Pattern 1: explicit pack/combo/set/kit keyword + count ─────────────

    @Test
    void packWithExplicitMultiplierDetectsCount() {
        assertThat(service.detectarCantidadUnidades("Pack x3 Remeras", "Remera")).isEqualTo(3);
    }

    @Test
    void comboWithExplicitMultiplierDetectsCount() {
        assertThat(service.detectarCantidadUnidades("Combo x2 Medias deportivas", "Medias")).isEqualTo(2);
    }

    @Test
    void packDeNDetectsCount() {
        assertThat(service.detectarCantidadUnidades("Pack de 3 medias deportivas", "Medias")).isEqualTo(3);
    }

    @Test
    void setKeywordWithPiezasDetectsCount() {
        assertThat(service.detectarCantidadUnidades("Set 3 piezas ropa interior", "Calzoncillos")).isEqualTo(3);
    }

    @Test
    void keywordWithGarmentWordBetweenAndNearbyXCountDetectsCount() {
        // keyword ... garment noun ... "xN" within the 20-char proximity window.
        assertThat(service.detectarCantidadUnidades("Pack Remeras x3", "Remera")).isEqualTo(3);
    }

    @Test
    void keywordFarFromXCountDoesNotFalselyMatch() {
        // "pack" and "x12" are far apart (>20 chars) — must NOT couple into a false pack count.
        assertThat(service.detectarCantidadUnidades(
                "Pack de regalos surtidos para mama modelo x12", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void substringInsideUnrelatedWordIsNotTreatedAsPackKeyword() {
        // "pack" appears inside "Backpacker" but is not a standalone word — must not match \bpack\b.
        assertThat(service.detectarCantidadUnidades("Mochila Backpacker x2", "Mochila")).isEqualTo(1);
    }

    // ── Pattern 2: N + garment-plural noun (same-garment repetition) ───────

    @Test
    void leadingIntegerAdjacentToPluralGarmentDetectsCount() {
        assertThat(service.detectarCantidadUnidades("3 remeras básicas algodón", "Remera")).isEqualTo(3);
    }

    @Test
    void twoBuzosIsDetectedAsPackOfTwo() {
        assertThat(service.detectarCantidadUnidades("2 buzos canguro", "Buzo")).isEqualTo(2);
    }

    // ── Pattern 3: "N piezas/prendas/unidades" ──────────────────────────────

    @Test
    void nPiezasMarkerDetectsCount() {
        assertThat(service.detectarCantidadUnidades("Set 2 piezas", "Conjunto")).isEqualTo(2);
    }

    // ── Pattern 4: distinct-garment combo (torso+piernas) -> fixed 2 ───────

    @Test
    void torsoPlusPiernasComboFixedAtTwo() {
        assertThat(service.detectarCantidadUnidades("Buzo + pantalón jogging", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoTopAndShortFixedAtTwo() {
        assertThat(service.detectarCantidadUnidades("Conjunto Top y Short", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoDeportivoWithoutExplicitCountFixedAtTwo() {
        // "Conjunto deportivo mujer" matches torso(remera/musculosa "top")+piernas? —
        // per spec scenario this is evaluated independent of category text alone;
        // explicit torso+piernas keyword adjacency is what matters, not the word
        // "conjunto" itself. Use a name that actually satisfies both blocks.
        assertThat(service.detectarCantidadUnidades("Conjunto musculosa y short deportivo", "Conjunto"))
                .isEqualTo(2);
    }

    // ── Negative / ambiguous cases -> MUST default to 1 ─────────────────────

    @Test
    void skuModelNumberIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Air Max 90", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void modelNumberWithSizeIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Zapatilla modelo 3200 talle 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Talle 38 a 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeWordedIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Conjunto talles 2 al 3", "Conjunto")).isEqualTo(1);
    }

    @Test
    void colorCountIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Remera disponible en 3 colores", "Remera")).isEqualTo(1);
    }

    @Test
    void singularGarmentIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Remera básica algodón", "Remera")).isEqualTo(1);
    }

    @Test
    void modelYearIsNotAPack() {
        assertThat(service.detectarCantidadUnidades("Buzo Modelo 2024", "Buzo")).isEqualTo(1);
    }

    @Test
    void keywordWithoutResolvableCountDefaultsToOne() {
        assertThat(service.detectarCantidadUnidades("Combo verano", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void ordinaryProductWithoutAnyPatternDefaultsToOne() {
        assertThat(service.detectarCantidadUnidades("Zapatillas Nike Vomero 17", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void countAboveCapDefaultsToOne() {
        // "Pack x99" exceeds the sane max (12) -> treated as ambiguous/model-like.
        assertThat(service.detectarCantidadUnidades("Pack x99 Remeras", "Remera")).isEqualTo(1);
    }
}
