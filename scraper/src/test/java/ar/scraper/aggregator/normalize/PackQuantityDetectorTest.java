package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
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
@Epic("Normalization")
@Feature("Pack Quantity")
@DisplayName("PackQuantityDetector — pack/combo unit-count detection")
class PackQuantityDetectorTest {

    private final PackQuantityDetector detector = new PackQuantityDetector();

    // ── Pattern 1: explicit pack/combo/set/kit keyword + count ─────────────

    @Test
    void packWithExplicitMultiplierDetectsCount() {
        Allure.parameter("texto", "Pack x3 Remeras");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("Pack x3 Remeras", "Remera")).isEqualTo(3);
    }

    @Test
    void comboWithExplicitMultiplierDetectsCount() {
        Allure.parameter("texto", "Combo x2 Medias deportivas");
        Allure.parameter("categoriaResuelta", "Medias");
        assertThat(detector.detectar("Combo x2 Medias deportivas", "Medias")).isEqualTo(2);
    }

    @Test
    void packDeNDetectsCount() {
        Allure.parameter("texto", "Pack de 3 medias deportivas");
        Allure.parameter("categoriaResuelta", "Medias");
        assertThat(detector.detectar("Pack de 3 medias deportivas", "Medias")).isEqualTo(3);
    }

    @Test
    void setKeywordWithPiezasDetectsCount() {
        Allure.parameter("texto", "Set 3 piezas ropa interior");
        Allure.parameter("categoriaResuelta", "Calzoncillos");
        assertThat(detector.detectar("Set 3 piezas ropa interior", "Calzoncillos")).isEqualTo(3);
    }

    @Test
    void keywordWithGarmentWordBetweenAndNearbyXCountDetectsCount() {
        // keyword ... garment noun ... "xN" within the 20-char proximity window.
        Allure.parameter("texto", "Pack Remeras x3");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("Pack Remeras x3", "Remera")).isEqualTo(3);
    }

    @Test
    void keywordFarFromXCountDoesNotFalselyMatch() {
        // "pack" and "x12" are far apart (>20 chars) — must NOT couple into a false pack count.
        Allure.parameter("texto", "Pack de regalos surtidos para mama modelo x12");
        Allure.parameter("categoriaResuelta", "Indumentaria");
        assertThat(detector.detectar(
                "Pack de regalos surtidos para mama modelo x12", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void substringInsideUnrelatedWordIsNotTreatedAsPackKeyword() {
        // "pack" appears inside "Backpacker" but is not a standalone word — must not match \bpack\b.
        Allure.parameter("texto", "Mochila Backpacker x2");
        Allure.parameter("categoriaResuelta", "Mochila");
        assertThat(detector.detectar("Mochila Backpacker x2", "Mochila")).isEqualTo(1);
    }

    // ── Pattern 2: N + garment-plural noun (same-garment repetition) ───────

    @Test
    void leadingIntegerAdjacentToPluralGarmentDetectsCount() {
        Allure.parameter("texto", "3 remeras básicas algodón");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("3 remeras básicas algodón", "Remera")).isEqualTo(3);
    }

    @Test
    void twoBuzosIsDetectedAsPackOfTwo() {
        Allure.parameter("texto", "2 buzos canguro");
        Allure.parameter("categoriaResuelta", "Buzo");
        assertThat(detector.detectar("2 buzos canguro", "Buzo")).isEqualTo(2);
    }

    // ── Pattern 3: "N piezas/prendas/unidades" ──────────────────────────────

    @Test
    void nPiezasMarkerDetectsCount() {
        Allure.parameter("texto", "Set 2 piezas");
        Allure.parameter("categoriaResuelta", "Conjunto");
        assertThat(detector.detectar("Set 2 piezas", "Conjunto")).isEqualTo(2);
    }

    // ── Pattern 4: distinct-garment combo (torso+piernas) -> fixed 2 ───────

    @Test
    void torsoPlusPiernasComboFixedAtTwo() {
        Allure.parameter("texto", "Buzo + pantalón jogging");
        Allure.parameter("categoriaResuelta", "Conjunto");
        assertThat(detector.detectar("Buzo + pantalón jogging", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoTopAndShortFixedAtTwo() {
        Allure.parameter("texto", "Conjunto Top y Short");
        Allure.parameter("categoriaResuelta", "Conjunto");
        assertThat(detector.detectar("Conjunto Top y Short", "Conjunto")).isEqualTo(2);
    }

    @Test
    void conjuntoDeportivoWithoutExplicitCountFixedAtTwo() {
        // "Conjunto deportivo mujer" matches torso(remera/musculosa "top")+piernas? —
        // per spec scenario this is evaluated independent of category text alone;
        // explicit torso+piernas keyword adjacency is what matters, not the word
        // "conjunto" itself. Use a name that actually satisfies both blocks.
        Allure.parameter("texto", "Conjunto musculosa y short deportivo");
        Allure.parameter("categoriaResuelta", "Conjunto");
        assertThat(detector.detectar("Conjunto musculosa y short deportivo", "Conjunto"))
                .isEqualTo(2);
    }

    // ── Negative / ambiguous cases -> MUST default to 1 ─────────────────────

    @Test
    void skuModelNumberIsNotAPack() {
        Allure.parameter("texto", "Air Max 90");
        Allure.parameter("categoriaResuelta", "Zapatilla Running");
        assertThat(detector.detectar("Air Max 90", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void modelNumberWithSizeIsNotAPack() {
        Allure.parameter("texto", "Zapatilla modelo 3200 talle 42");
        Allure.parameter("categoriaResuelta", "Zapatilla");
        assertThat(detector.detectar("Zapatilla modelo 3200 talle 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeIsNotAPack() {
        Allure.parameter("texto", "Talle 38 a 42");
        Allure.parameter("categoriaResuelta", "Zapatilla");
        assertThat(detector.detectar("Talle 38 a 42", "Zapatilla")).isEqualTo(1);
    }

    @Test
    void sizeRangeWordedIsNotAPack() {
        Allure.parameter("texto", "Conjunto talles 2 al 3");
        Allure.parameter("categoriaResuelta", "Conjunto");
        assertThat(detector.detectar("Conjunto talles 2 al 3", "Conjunto")).isEqualTo(1);
    }

    @Test
    void colorCountIsNotAPack() {
        Allure.parameter("texto", "Remera disponible en 3 colores");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("Remera disponible en 3 colores", "Remera")).isEqualTo(1);
    }

    @Test
    void singularGarmentIsNotAPack() {
        Allure.parameter("texto", "Remera básica algodón");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("Remera básica algodón", "Remera")).isEqualTo(1);
    }

    @Test
    void modelYearIsNotAPack() {
        Allure.parameter("texto", "Buzo Modelo 2024");
        Allure.parameter("categoriaResuelta", "Buzo");
        assertThat(detector.detectar("Buzo Modelo 2024", "Buzo")).isEqualTo(1);
    }

    @Test
    void buzoJoggerStyleDescriptorIsNotAPack() {
        // "buzo" (torso) and "jogger" (piernas) both match, but no connector
        // separates them — "jogger" describes the buzo's cut, not a second
        // garment. Regression test for the torso+piernas false-positive that
        // was silently halving cantidadUnidades (and thus precioUnitario) for
        // ordinary single-garment products before the connector requirement.
        Allure.parameter("texto", "Buzo Canguro Jogger Hombre");
        Allure.parameter("categoriaResuelta", "Buzo");
        assertThat(detector.detectar("Buzo Canguro Jogger Hombre", "Buzo")).isEqualTo(1);
    }

    @Test
    void camperaConCapuchaJoggerStyleIsNotAPack() {
        // "con" must NOT count as a combo connector: "campera con capucha"
        // describes one jacket's detail, not a second garment joined to it.
        Allure.parameter("texto", "Campera con capucha estilo jogger");
        Allure.parameter("categoriaResuelta", "Campera");
        assertThat(detector.detectar("Campera con capucha estilo jogger", "Campera")).isEqualTo(1);
    }

    @Test
    void keywordWithoutResolvableCountDefaultsToOne() {
        Allure.parameter("texto", "Combo verano");
        Allure.parameter("categoriaResuelta", "Indumentaria");
        assertThat(detector.detectar("Combo verano", "Indumentaria")).isEqualTo(1);
    }

    @Test
    void ordinaryProductWithoutAnyPatternDefaultsToOne() {
        Allure.parameter("texto", "Zapatillas Nike Vomero 17");
        Allure.parameter("categoriaResuelta", "Zapatilla Running");
        assertThat(detector.detectar("Zapatillas Nike Vomero 17", "Zapatilla Running")).isEqualTo(1);
    }

    @Test
    void countAboveCapDefaultsToOne() {
        // "Pack x99" exceeds the sane max (12) -> treated as ambiguous/model-like.
        Allure.parameter("texto", "Pack x99 Remeras");
        Allure.parameter("categoriaResuelta", "Remera");
        assertThat(detector.detectar("Pack x99 Remeras", "Remera")).isEqualTo(1);
    }
}
