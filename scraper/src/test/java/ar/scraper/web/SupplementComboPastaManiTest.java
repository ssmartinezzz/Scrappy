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
 * Peanut butter was being offered as protein powder.
 *
 * <p>The format veto for "Proteína en Polvo" keyed on the literal phrase
 * {@code " con proteina"}, so of two products that are both, literally, jars of
 * peanut butter, one was vetoed and the other was not — the only difference being
 * that one label says "con Proteína" and the other says "Alta Proteína". Keying on
 * the connector is arbitrary; "alta proteína", "rica en proteína", "fuente de
 * proteína" and "+ proteína" all leak through it.</p>
 *
 * <p>The stable signal is word ORDER, not the connector. A powder announces itself
 * head-first — "Proteína Whey", "Whey Protein Isolate" — while a fortified food
 * leads with the food and mentions protein afterwards as a claim: "Pasta de Maní …
 * Alta Proteína". Flavour names are safe under this rule because a flavour always
 * trails the head ("Proteína Whey sabor Cookies &amp; Cream").</p>
 *
 * <p>Every name here is real, taken from the live catalog on 2026-08-11.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Supplement combo")
@Story("Format veto")
@DisplayName("SupplementCombo — peanut butter is not protein powder")
class SupplementComboPastaManiTest {

    private final SupplementCombo combo = new SupplementCombo(new RecommendationService());

    private Product p(String categoria, String nombre) {
        return new Product("entreno", nombre, 10000, null,
                "https://test/" + Math.abs(nombre.hashCode()), "https://img/x.jpg",
                categoria, "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "suplementos", false);
    }

    /** The subtype this product lands in when classified on its own, or "" for none. */
    private String subtipoDe(Product producto) {
        List<OutfitService.SupplementPick> picks =
                combo.armarComboSuplementos(List.of(producto), 0, null);
        return picks.isEmpty() ? "" : picks.get(0).tipo();
    }

    @Test
    @DisplayName("'Alta Proteína' on a jar of peanut butter is a claim, not a powder")
    void laPastaDeManiConClaimDeProteinaNoEsPolvo() {
        Product entrenuts = p("Proteína",
                "ENTRENUTS Pasta De Maní Entrenuts Protein Sin TACC 370g Alta Proteína");

        assertThat(subtipoDe(entrenuts)).isNotEqualTo("Proteína en Polvo");
    }

    @Test
    @DisplayName("the connector used by the label must not decide the outcome")
    void lasDosPastasDeManiSeTratanIgual() {
        // Same product shape, different wording. Before the fix, "con Proteina" was
        // vetoed and "Alta Proteína" was not — the whole bug in two lines.
        Product conProteina = p("Proteína",
                "BULL BAR Pasta de Mani con Proteina Power Cookies 420g - Sin TACC");
        Product altaProteina = p("Proteína",
                "ENTRENUTS Pasta De Maní Entrenuts Protein Sin TACC 370g Alta Proteína");

        assertThat(subtipoDe(altaProteina)).isEqualTo(subtipoDe(conProteina));
    }

    @Test
    @DisplayName("a whey isolate flavoured peanut butter is still protein powder")
    void elIsolateConSaborAManiSigueSiendoPolvo() {
        // The head noun is "Proteína"; "peanut butter" is a flavour trailing it.
        // This is the case a blunt "mentions a food noun" veto would have broken.
        Product raw = p("Proteína",
                "RAW Proteína Itholate 2lb 992G - 25 Serv - CHOCOLATE PEANUT BUTTER");

        assertThat(subtipoDe(raw)).isEqualTo("Proteína en Polvo");
    }

    @Test
    @DisplayName("flavour names that reuse food nouns stay powders")
    void losSaboresConNombreDeComidaNoVetan() {
        for (String nombre : List.of(
                "Whey Protein sabor Leche Chocolatada 1kg",
                "Proteína Whey sabor Yogur Griego 900g",
                "Whey Protein Cookies & Cream 2lb",
                "Proteína Isolate sabor Banana Split 1kg")) {
            assertThat(subtipoDe(p("Proteína", nombre)))
                    .as("%s", nombre).isEqualTo("Proteína en Polvo");
        }
    }

    @Test
    @DisplayName("fortified foods that lead with the food are vetoed however they phrase it")
    void losAlimentosFortificadosSeVetanConCualquierRedaccion() {
        for (String nombre : List.of(
                "Leche con Proteína 1L",
                "Avena Alta en Proteína 500g",
                "Galletitas rica en proteina 120g",
                "Pasta de Maní 380g fuente de proteina",
                "Yogur Griego 20g de proteina por pote")) {
            assertThat(subtipoDe(p("Proteína", nombre)))
                    .as("%s", nombre).isNotEqualTo("Proteína en Polvo");
        }
    }

    @Test
    @DisplayName("the builder does not offer peanut butter when asked for powder")
    void elBuilderNoOfrecePastaDeManiComoPolvo() {
        List<Product> catalogo = List.of(
                p("Proteína", "ENTRENUTS Pasta De Maní Entrenuts Protein Sin TACC 370g Alta Proteína"),
                p("Proteína", "BULL BAR Pasta de Mani con Proteina Power Cookies 420g - Sin TACC"),
                p("Proteína", "RAW Proteína Itholate 2lb 992G - 25 Serv - CHOCOLATE PEANUT BUTTER"));

        List<OutfitService.SupplementPick> picks =
                combo.armarComboSuplementos(catalogo, 0, Set.of("Proteína en Polvo"));

        assertThat(picks).hasSize(1);
        assertThat(picks.get(0).nombre()).contains("Itholate");
    }
}
