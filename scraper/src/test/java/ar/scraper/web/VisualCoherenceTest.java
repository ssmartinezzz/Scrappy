package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link VisualCoherence} — the three outfit-level rules that read
 * {@code Product.visual()}, which until now no assembler consulted at all.
 *
 * <p>The load-bearing property across every rule is ABSTENTION: the visual
 * attributes come from a zero-shot classifier that declines when unsure, so an
 * empty attribute must mean "no opinion", never "clash". A rule that punished
 * missing data would silently reshape the whole catalog's ranking.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Outfit Building")
@Story("Visual coherence")
@DisplayName("VisualCoherence — print, fit and colour rules")
class VisualCoherenceTest {

    private Product prenda(String categoria, String fit, String estampado, String color) {
        return new Product("TestSitio", categoria + "-" + fit + estampado + color, 20000, null,
                "https://test/" + categoria + fit + estampado + color, "https://img/t.jpg",
                categoria, "hombre", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                true, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                new Product.VisualAttrs(fit, estampado, "", color));
    }

    private double coherencia(String slot, Product candidato, Map<String, Product> elegidos) {
        return VisualCoherence.coherencia(slot, candidato, elegidos);
    }

    // ─── Abstention ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no visual data at all → no opinion")
    void sinAtributosVisualesNoHayPenalizacion() {
        Product torso = prenda("Remera", "", "", "");
        Product piernas = prenda("Short", "", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an empty attribute on either side never triggers its rule")
    void unLadoVacioNoDisparaLaRegla() {
        Product torsoEstampado = prenda("Remera", "oversize", "estampado", "rojo");
        Product piernasSinDatos = prenda("Short", "", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernasSinDatos,
                Map.of(OutfitService.SLOT_TORSO, torsoEstampado))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the first slot of an outfit has nothing to clash with")
    void elPrimerSlotNuncaSePenaliza() {
        Product torso = prenda("Remera", "oversize", "estampado", "rojo");

        assertThat(coherencia(OutfitService.SLOT_TORSO, torso, Map.of())).isEqualTo(1.0);
    }

    // ─── Rule 1: one print per outfit ────────────────────────────────────────

    @Test
    @DisplayName("two prints in one outfit are penalized")
    void dosEstampadosSePenalizan() {
        Product torso = prenda("Remera", "", "estampado", "");
        Product piernas = prenda("Short", "", "estampado", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isLessThan(1.0);
    }

    @Test
    @DisplayName("a print over a plain garment is fine")
    void unSoloEstampadoEsCorrecto() {
        Product torso = prenda("Remera", "", "estampado", "");
        Product piernas = prenda("Short", "", "liso", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the print rule spans every slot, accessories included")
    void elEstampadoSeCuentaEnTodoElOutfit() {
        Product torso = prenda("Remera", "", "estampado", "");
        Product gorra = prenda("Gorra", "", "estampado", "");

        assertThat(coherencia(OutfitService.SLOT_ACCESORIO, gorra,
                Map.of(OutfitService.SLOT_TORSO, torso))).isLessThan(1.0);
    }

    // ─── Rule 2: fit coherence ───────────────────────────────────────────────

    @Test
    @DisplayName("oversize on top and bottom is penalized")
    void oversizeArribaYAbajoSePenaliza() {
        Product torso = prenda("Remera", "oversize", "", "");
        Product piernas = prenda("Short", "oversize", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isLessThan(1.0);
    }

    @Test
    @DisplayName("entallado on top and bottom is penalized too")
    void entalladoArribaYAbajoSePenaliza() {
        Product torso = prenda("Remera", "entallado", "", "");
        Product piernas = prenda("Calza", "entallado", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isLessThan(1.0);
    }

    @Test
    @DisplayName("regular fit is the neutral one — it clashes with nothing")
    void elFitRegularNoChocaConNada() {
        Product torso = prenda("Remera", "regular", "", "");
        Product piernas = prenda("Short", "regular", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("oversize top with a slim bottom is a deliberate look, not a clash")
    void oversizeArribaConEntalladoAbajoEsUnLookValido() {
        Product torso = prenda("Buzo", "oversize", "", "");
        Product piernas = prenda("Calza", "entallado", "", "");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("fit is a garment rule — shoes and accessories are exempt")
    void elFitSoloAplicaAPrendasDeTorsoYPiernas() {
        // "Oversize sneakers" is not a thing the fit rule should have an opinion about.
        Product torso = prenda("Remera", "oversize", "", "");
        Product calzado = prenda("Zapatilla", "oversize", "", "");

        assertThat(coherencia(OutfitService.SLOT_CALZADO, calzado,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    // ─── Rule 3: colour coordination ─────────────────────────────────────────

    @Test
    @DisplayName("a neutral goes with anything")
    void losNeutrosCombinanConTodo() {
        Product torso = prenda("Remera", "", "", "verde");

        for (String neutro : List.of("negro", "blanco", "gris", "beige", "marron")) {
            Product piernas = prenda("Short", "", "", neutro);
            assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                    Map.of(OutfitService.SLOT_TORSO, torso)))
                    .as("neutro %s", neutro).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("the same colour top and bottom is monochrome, not a clash")
    void elMismoColorNoSePenaliza() {
        Product torso = prenda("Remera", "", "", "azul");
        Product piernas = prenda("Short", "", "", "azul");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("neighbours on the colour wheel harmonize")
    void losColoresVecinosArmonizan() {
        // azul↔celeste is one step, azul↔verde is two (through celeste) — both
        // inside the analogous quarter-turn the rule allows.
        Product torsoAzul = prenda("Remera", "", "", "azul");

        for (String vecino : List.of("celeste", "verde", "violeta", "rosa")) {
            Product piernas = prenda("Short", "", "", vecino);
            assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                    Map.of(OutfitService.SLOT_TORSO, torsoAzul)))
                    .as("vecino %s", vecino).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("colours far apart on the wheel clash")
    void losColoresLejanosChocan() {
        Product torsoRojo = prenda("Remera", "", "", "rojo");

        for (String lejano : List.of("verde", "celeste", "azul")) {
            Product piernas = prenda("Short", "", "", lejano);
            assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                    Map.of(OutfitService.SLOT_TORSO, torsoRojo)))
                    .as("lejano %s", lejano).isLessThan(1.0);
        }
    }

    @Test
    @DisplayName("an unknown colour name is treated as no opinion")
    void unColorFueraDeLaPaletaNoOpina() {
        // The palette is fixed on the Python side; a name this class does not know
        // must abstain rather than guess a position on the wheel.
        Product torso = prenda("Remera", "", "", "rojo");
        Product piernas = prenda("Short", "", "", "fucsia-neon");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isEqualTo(1.0);
    }

    // ─── Composition ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the three rules compound but never reach zero")
    void lasTresReglasSeMultiplicanYNuncaLleganACero() {
        Product torso = prenda("Remera", "oversize", "estampado", "rojo");
        Product piernas = prenda("Short", "oversize", "estampado", "verde");

        double c = coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso));

        assertThat(c).isLessThan(0.5);
        // A weight, never a filter — the worst possible outfit stays reachable,
        // exactly like the ML factor in weightedRandomPick.
        assertThat(c).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a clash against any already-chosen garment counts, not just the last one")
    void seComparaContraTodosLosSlotsYaElegidos() {
        Product torso = prenda("Remera", "", "liso", "azul");
        Product piernas = prenda("Short", "", "estampado", "celeste");
        Product calzadoEstampado = prenda("Zapatilla", "", "estampado", "negro");

        double c = VisualCoherence.coherencia(OutfitService.SLOT_CALZADO, calzadoEstampado,
                Map.of(OutfitService.SLOT_TORSO, torso, OutfitService.SLOT_PIERNAS, piernas));

        assertThat(c).isLessThan(1.0);
    }

    @Test
    @DisplayName("a coherent outfit is scored exactly 1.0, not merely close to it")
    void unOutfitCoherenteVale1() {
        Product torso = prenda("Remera", "oversize", "estampado", "azul");
        Product piernas = prenda("Short", "entallado", "liso", "negro");

        assertThat(coherencia(OutfitService.SLOT_PIERNAS, piernas,
                Map.of(OutfitService.SLOT_TORSO, torso))).isCloseTo(1.0, within(1e-9));
    }
}
