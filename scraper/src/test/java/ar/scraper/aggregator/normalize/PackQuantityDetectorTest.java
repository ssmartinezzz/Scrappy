package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PackQuantityDetector#detectar(String, String)},
 * the pack/combo unit-count detector (PR 1 of pack-pricing-detection).
 *
 * Pattern priority (per design):
 *   1) explicit pack/combo/set/kit keyword + count
 *   2) N + garment-plural noun adjacency
 *   3) N piezas/prendas/unidades
 *   4) distinct-garment combo (torso+piernas heuristic) -> fixed 2
 * Ambiguous numeric tokens (SKU/model numbers, size ranges, color counts)
 * MUST fall back to 1 (conservative default).
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 4 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
class PackQuantityDetectorTest {

    private final PackQuantityDetector detector = new PackQuantityDetector();

    // ── Pattern 1: explicit pack/combo/set/kit keyword + count ─────────────

    @Test
    void packWithExplicitMultiplierDetectsCount() {
        assertThat(detector.detectar("Pack x3 Remeras", "Remera")).isEqualTo(3);
    }

    @Test
    void comboWithExplicitMultiplierDetectsCount() {
        assertThat(detector.detectar("Combo x2 Medias deportivas", "Medias")).isEqualTo(2);
    }

    @Test
    void packDeNDetectsCount() {
        assertThat(detector.detectar("Pack de 3 medias deportivas", "Medias")).isEqualTo(3);
    }

    @Test
    void setKeywordWithPiezasDetectsCount() {
        assertThat(detector.detectar("Set 3 piezas ropa interior", "Calzoncillos")).isEqualTo(3);
    }

    @Test
    void keywordWithGarmentWordBetweenAndNearbyXCountDetectsCount() {
        // keyword ... garment noun ... "xN" within the 20-char proximity window.
        assertThat(detector.detectar("Pack Remeras x3", "Remera")).isEqualTo(3);
    }

    @Test
    void keywordFarFromXCountDoesNotFalselyMatch() {
        // "pack" and "x12" are far apart (>20 chars) — must NOT couple into a false pack count.
        assertThat(detector.detectar(
                "Pack de regalos surtidos para mama modelo x12", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void substringInsideUnrelatedWordIsNotTreatedAsPackKeyword() {
        // "pack" appears inside "Backpacker" but is not a standalone word — must not match \bpack\b.
        assertThat(detector.detectar("Mochila Backpacker x2", "Mochila")).isEqualTo(1);
    }

    // ── Pattern 2: N + garment-plural noun (same-garment repetition) ───────

    @Test
    void leadingIntegerAdjacentToPluralGarmentDetectsCount() {
        assertThat(detector.detectar("3 remeras básicas algodón", "Remera")).isEqualTo(3);
    }

    @Test
    void twoBuzosIsDetectedAsPackOfTwo() {
        assertThat(detector.detectar("2 buzos canguro", "Buzo")).isEqualTo(2);
    }

    // ── Pattern 3: "N piezas/prendas/unidades" ──────────────────────────────

    @Test
    void nPiezasMarkerDetectsCount() {
        assertThat(detector.detectar("Set 2 piezas", "Conjunto")).isEqualTo(2);
    }

    // ── Pattern 4: distinct-garment combo (torso+piernas) -> fixed 2 ───────

    @Test
    void torsoPlusPiernasComboFixedAtTwo() {
        assertThat(detector.detectar("Buzo + pantalón jogging", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoTopAndShortFixedAtTwo() {
        assertThat(detector.detectar("Conjunto Top y Short", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoDeportivoWithoutExplicitCountFixedAtTwo() {
        // "Conjunto deportivo mujer" matches torso(remera/musculosa "top")+piernas? —
        // per spec scenario this is evaluated independent of category text alone;
        // explicit torso+piernas keyword adjacency is what matters, not the word
        // "conjunto" itself. Use a name that actually satisfies both blocks.
        assertThat(detector.detectar("Conjunto musculosa y short deportivo", "Conjunto"))
                .isEqualTo(2);
    }

    // ── Negative / ambiguous cases -> MUST default to 1 ─────────────────────

    @Test
    void skuModelNumberIsNotAPack() {
        assertThat(detector.detectar("Air Max 90", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void modelNumberWithSizeIsNotAPack() {
        assertThat(detector.detectar("Zapatilla modelo 3200 talle 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeIsNotAPack() {
        assertThat(detector.detectar("Talle 38 a 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeWordedIsNotAPack() {
        assertThat(detector.detectar("Conjunto talles 2 al 3", "Conjunto")).isEqualTo(1);
    }

    @Test
    void colorCountIsNotAPack() {
        assertThat(detector.detectar("Remera disponible en 3 colores", "Remera")).isEqualTo(1);
    }

    @Test
    void singularGarmentIsNotAPack() {
        assertThat(detector.detectar("Remera básica algodón", "Remera")).isEqualTo(1);
    }

    @Test
    void modelYearIsNotAPack() {
        assertThat(detector.detectar("Buzo Modelo 2024", "Buzo")).isEqualTo(1);
    }

    @Test
    void buzoJoggerStyleDescriptorIsNotAPack() {
        // "buzo" (torso) and "jogger" (piernas) both match, but no connector
        // separates them — "jogger" describes the buzo's cut, not a second
        // garment. Regression test for the torso+piernas false-positive that
        // was silently halving cantidadUnidades (and thus precioUnitario) for
        // ordinary single-garment products before the connector requirement.
        assertThat(detector.detectar("Buzo Canguro Jogger Hombre", "Buzo")).isEqualTo(1);
    }

    @Test
    void camperaConCapuchaJoggerStyleIsNotAPack() {
        // "con" must NOT count as a combo connector: "campera con capucha"
        // describes one jacket's detail, not a second garment joined to it.
        assertThat(detector.detectar("Campera con capucha estilo jogger", "Campera")).isEqualTo(1);
    }

    @Test
    void keywordWithoutResolvableCountDefaultsToOne() {
        assertThat(detector.detectar("Combo verano", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void ordinaryProductWithoutAnyPatternDefaultsToOne() {
        assertThat(detector.detectar("Zapatillas Nike Vomero 17", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void countAboveCapDefaultsToOne() {
        // "Pack x99" exceeds the sane max (12) -> treated as ambiguous/model-like.
        assertThat(detector.detectar("Pack x99 Remeras", "Remera")).isEqualTo(1);
    }
}
