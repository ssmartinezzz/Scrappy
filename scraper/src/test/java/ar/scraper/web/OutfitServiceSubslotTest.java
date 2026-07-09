package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Outfit Orchestration")
@Feature("Outfit Building")
@Story("Subslots")
@DisplayName("OutfitService — Category Subslot Mapping")
class OutfitServiceSubslotTest {

    private RecommendationService recommendationService;
    private OutfitService outfitService;
    private Map<String, String> categoriaSubslot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        wireOutfitService();
    }

    @Step("Wire OutfitService with mocked collaborators")
    @SuppressWarnings("unchecked")
    private void wireOutfitService() throws Exception {
        recommendationService = mock(RecommendationService.class);
        when(recommendationService.baseMlScore(any())).thenReturn(1.0);
        outfitService = new OutfitService(recommendationService);

        Field f = OutfitService.class.getDeclaredField("CATEGORIA_SUBSLOT");
        f.setAccessible(true);
        categoriaSubslot = (Map<String, String>) f.get(null);
    }

    // ── Taxonomy snapshot tests ───────────────────────────────────────────

    @Test
    void categoriaSubslot_remeraMapsTorsoBase() {
        assertThat(categoriaSubslot.get("Remera")).isEqualTo(OutfitService.SUBSLOT_TORSO_BASE);
    }

    @Test
    void categoriaSubslot_buzoMapsTorsoOuter() {
        assertThat(categoriaSubslot.get("Buzo")).isEqualTo(OutfitService.SUBSLOT_TORSO_OUTER);
    }

    @Test
    void categoriaSubslot_shortMapsPiernas() {
        assertThat(categoriaSubslot.get("Short")).isEqualTo(OutfitService.SLOT_PIERNAS);
    }

    @Test
    void categoriaSubslot_gorraAccesorioHead() {
        assertThat(categoriaSubslot.get("Gorra")).isEqualTo(OutfitService.SUBSLOT_ACCESORIO_HEAD);
    }

    @Test
    void categoriaSubslot_mediasAccesorioFeet() {
        assertThat(categoriaSubslot.get("Medias")).isEqualTo(OutfitService.SUBSLOT_ACCESORIO_FEET);
    }

    // ── armarPorCategorias integration tests ─────────────────────────────

    @Test
    void armarPorCategorias_remeraYBuzoPick2Items() {
        var remera = gymProd("Remera", "Remera deportiva", 3000);
        var buzo   = gymProd("Buzo", "Buzo hoodie", 8000);

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                List.of(remera, buzo), List.of("Remera", "Buzo"),
                100_000, null, OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(2);
        assertThat(result.slots()).extracting(OutfitService.SlotPick::slot)
                .containsExactlyInAnyOrder(OutfitService.SUBSLOT_TORSO_BASE, OutfitService.SUBSLOT_TORSO_OUTER);
    }

    @Test
    void armarPorCategorias_shortYJoggingPick1Item() {
        var short1  = gymProd("Short", "Short deportivo", 3000);
        var jogging = gymProd("Jogging", "Jogging slim", 5000);

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                List.of(short1, jogging), List.of("Short", "Jogging"),
                100_000, null, OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).slot()).isEqualTo(OutfitService.SLOT_PIERNAS);
    }

    @Test
    void armarPorCategorias_zapatillaYZapatillaRunningPick1Item() {
        var zap    = prod("Zapatilla", "Zapatilla urbana", 8000);
        var zapRun = prod("Zapatilla Running", "Zapatilla running x", 10_000);

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                List.of(zap, zapRun), List.of("Zapatilla", "Zapatilla Running"),
                100_000, null, OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).slot()).isEqualTo(OutfitService.SLOT_CALZADO);
    }

    @Test
    void armarPorCategorias_emptyCatalog_returnsNoFit() {
        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                List.of(), List.of("Remera", "Buzo"),
                100_000, null, OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).isEmpty();
    }

    @Test
    void armarPorCategorias_budgetTooLow_returnsEmptySlots() {
        Allure.parameter("presupuesto", 1);
        var remera = gymProd("Remera", "Remera deportiva", 5000);

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                List.of(remera), List.of("Remera"),
                1, null, OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** gymrat=true — required for torso and piernas slots to pass the eligibility gate. */
    private Product gymProd(String categoria, String nombre, double precio) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                null, categoria, "hombre", List.of(), null, "Marca", "indumentaria", true);
    }

    /** gymrat=false — calzado and accesorio slots do not require gymrat. */
    private Product prod(String categoria, String nombre, double precio) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                null, categoria, "hombre", List.of(), null, "Marca");
    }
}
