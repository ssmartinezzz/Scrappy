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
 * {@link VisualCoherence} wired into the three assemblers: the random gym
 * assembler ({@code armar}), the MCKP budget builder and its greedy fallback.
 *
 * <p>Each test isolates the visual axis by holding everything else equal — same
 * price, same ML score, same brand — so a difference in outcome can only come
 * from {@code Product.visual()}.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Outfit Building")
@Story("Visual coherence")
@DisplayName("Assemblers — visual coherence wiring")
class OutfitVisualCoherenceIntegrationTest {

    private final OutfitService service = new OutfitService(new RecommendationService());

    private Product prenda(String nombre, String categoria, double precio, int scoreP,
                           String fit, String estampado, String color) {
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/t.jpg",
                categoria, "hombre", List.of(),
                new Product.MlScore(scoreP, "", false, "estable", 50),
                "Marca", "indumentaria", true, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                new Product.VisualAttrs(fit, estampado, "", color));
    }

    private String urlDelSlot(OutfitService.Outfit outfit, String slot) {
        return outfit.slots().stream()
                .filter(s -> slot.equals(s.slot()))
                .map(OutfitService.SlotPick::url)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("armar() prefers a plain bottom under a printed top")
    void armarEvitaElSegundoEstampado() {
        // Only the torso is printed, and both shorts are identical apart from that.
        Product torso = prenda("Remera Estampada", "Remera", 20000, 50, "regular", "estampado", "negro");
        Product shortEstampado = prenda("Short Estampado", "Short", 20000, 50, "regular", "estampado", "negro");
        Product shortLiso = prenda("Short Liso", "Short", 20000, 50, "regular", "liso", "negro");
        Product zapatilla = prenda("Zapatilla", "Zapatilla", 20000, 50, "", "", "negro");

        List<Product> catalogo = List.of(torso, shortEstampado, shortLiso, zapatilla);

        int liso = 0;
        int estampado = 0;
        for (int i = 0; i < 400; i++) {
            OutfitService.Outfit outfit = service.armar(catalogo, "hombre");
            String url = urlDelSlot(outfit, OutfitService.SLOT_PIERNAS);
            if (shortLiso.url().equals(url)) liso++;
            if (shortEstampado.url().equals(url)) estampado++;
        }

        // Weights are 1.0 vs 0.5, so roughly 2:1. Asserting a plain majority keeps
        // this immune to sampling noise while still failing if the wiring is absent.
        assertThat(liso).isGreaterThan(estampado);
        // Still a weight, not a filter — the printed short must remain reachable.
        assertThat(estampado).isGreaterThan(0);
    }

    @Test
    @DisplayName("armar() prefers a colour that coordinates with the top already chosen")
    void armarPrefiereUnColorQueCombina() {
        Product torso = prenda("Remera Roja", "Remera", 20000, 50, "regular", "liso", "rojo");
        Product shortVerde = prenda("Short Verde", "Short", 20000, 50, "regular", "liso", "verde");
        Product shortNaranja = prenda("Short Naranja", "Short", 20000, 50, "regular", "liso", "naranja");
        Product zapatilla = prenda("Zapatilla", "Zapatilla", 20000, 50, "", "", "negro");

        List<Product> catalogo = List.of(torso, shortVerde, shortNaranja, zapatilla);

        int naranja = 0;
        int verde = 0;
        for (int i = 0; i < 400; i++) {
            OutfitService.Outfit outfit = service.armar(catalogo, "hombre");
            String url = urlDelSlot(outfit, OutfitService.SLOT_PIERNAS);
            if (shortNaranja.url().equals(url)) naranja++;
            if (shortVerde.url().equals(url)) verde++;
        }

        // rojo↔naranja is one step on the wheel; rojo↔verde is three.
        assertThat(naranja).isGreaterThan(verde);
    }

    @Test
    @DisplayName("MCKP picks the coherent combination when scores are tied")
    void elBuilderMckpPrefiereLaCombinacionCoherente() {
        // Identical price and identical ML score on both shorts: the optimizer has
        // no reason to prefer either one except the print rule.
        Product torso = prenda("Remera Estampada", "Remera", 20000, 10, "regular", "estampado", "negro");
        Product shortEstampado = prenda("Short Estampado", "Short", 20000, 10, "regular", "estampado", "negro");
        Product shortLiso = prenda("Short Liso", "Short", 20000, 10, "regular", "liso", "negro");

        OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                List.of(torso, shortEstampado, shortLiso),
                List.of("Remera", "Short"), 100000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false, List.of(), "gym");

        assertThat(r.slots()).extracting(OutfitService.SlotPick::url)
                .contains(shortLiso.url())
                .doesNotContain(shortEstampado.url());
    }

    @Test
    @DisplayName("MCKP still takes a clashing item over an empty slot")
    void elBuilderMckpNoDejaUnSlotVacioPorIncoherencia() {
        // The only available short clashes on all three axes. A penalty must never
        // turn into a veto: a slightly loud outfit beats an incomplete one.
        Product torso = prenda("Remera", "Remera", 20000, 10, "oversize", "estampado", "rojo");
        Product shortChocante = prenda("Short Chocante", "Short", 20000, 10, "oversize", "estampado", "verde");

        OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                List.of(torso, shortChocante), List.of("Remera", "Short"), 100000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false, List.of(), "gym");

        assertThat(r.slots()).extracting(OutfitService.SlotPick::url)
                .contains(shortChocante.url());
    }

    @Test
    @DisplayName("greedy prefers the coherent candidate among affordable ones")
    void elGreedyPrefiereElCandidatoCoherente() {
        Product torso = prenda("Remera Estampada", "Remera", 20000, 10, "regular", "estampado", "negro");
        Product shortEstampado = prenda("Short Estampado", "Short", 20000, 10, "regular", "estampado", "negro");
        Product shortLiso = prenda("Short Liso", "Short", 20000, 10, "regular", "liso", "negro");

        List<Product> catalogo = List.of(torso, shortEstampado, shortLiso);

        // Greedy shuffles its pool, so run it enough times that "picked the first of
        // a shuffled pair" would show up. It must be the plain one every single time.
        for (int i = 0; i < 50; i++) {
            OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                    catalogo, List.of("Remera", "Short"), 100000, "hombre",
                    OutfitService.FeedbackModel.empty(), Set.of(), true, List.of(), "gym");

            assertThat(r.slots()).extracting(OutfitService.SlotPick::url)
                    .doesNotContain(shortEstampado.url());
        }
    }

    @Test
    @DisplayName("greedy honours the hard budget over coherence")
    void elGreedyPrefiereElPresupuestoSobreLaCoherencia() {
        // The coherent short is unaffordable; the clashing one fits. Budget wins —
        // coherence reorders candidates, it never relaxes the ceiling.
        Product torso = prenda("Remera", "Remera", 20000, 10, "regular", "estampado", "negro");
        Product caroCoherente = prenda("Short Liso Caro", "Short", 500000, 10, "regular", "liso", "negro");
        Product baratoChocante = prenda("Short Estampado", "Short", 10000, 10, "regular", "estampado", "negro");

        OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                List.of(torso, caroCoherente, baratoChocante), List.of("Remera", "Short"),
                40000, "hombre", OutfitService.FeedbackModel.empty(), Set.of(), true,
                List.of(), "gym");

        assertThat(r.slots()).extracting(OutfitService.SlotPick::url)
                .contains(baratoChocante.url());
        assertThat(r.totalEstimado()).isLessThanOrEqualTo(40000);
    }
}
