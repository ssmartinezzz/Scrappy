package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Food subtypes of the supplement builder.
 *
 * <p>Everything the classifier tags "Alimentos" already reached the pool, but no subtype
 * claimed it, so it was invisible in the builder — and so was every product the powder
 * veto had pushed out ("Leche con Proteína", "Avena Alta en Proteína"): vetoed from the
 * only bucket that could have held them, and offered nowhere.</p>
 *
 * <p>The risk of adding them is the mirror of the bug the powder veto fixed: a culinary
 * noun is also how a powder names its FLAVOUR. {@code esElSaborDeUnPolvo} reads the same
 * word order from the other side, so both halves of this file are asserted here — the
 * food that must be claimed and the whey that must not be.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Supplement combo")
@Story("Food subtypes")
@DisplayName("SupplementCombo — food categories")
class SupplementComboAlimentosTest {

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
    @DisplayName("each food subtype claims the product that names it")
    void cadaSubtipoDeComidaSeQuedaConSuProducto() {
        assertThat(subtipoDe(p("Alimentos", "ENTRENUTS Pasta De Maní Natural 370g")))
                .isEqualTo("Pasta de Maní");
        assertThat(subtipoDe(p("Alimentos", "Avena Instantánea sabor Vainilla 1kg")))
                .isEqualTo("Avena / Harina");
        assertThat(subtipoDe(p("Alimentos", "Granola Artesanal con Semillas 350g")))
                .isEqualTo("Granola / Cereal");
        assertThat(subtipoDe(p("Alimentos", "Tostadas de Arroz sin TACC 120g")))
                .isEqualTo("Galletas / Tostadas");
        assertThat(subtipoDe(p("Alimentos", "Fideos de Konjac 200g")))
                .isEqualTo("Fideos / Arroz");
        assertThat(subtipoDe(p("Alimentos", "SmartDIET Puré de Palmitos 300g")))
                .isEqualTo("Snack Salado");
        assertThat(subtipoDe(p("Alimentos", "Mix de Frutos Secos Premium 500g")))
                .isEqualTo("Frutos Secos");
        assertThat(subtipoDe(p("Alimentos", "Yerba Mate Orgánica 1kg")))
                .isEqualTo("Infusiones");
        assertThat(subtipoDe(p("Alimentos", "Mermelada de Frutilla sin azúcar 250g")))
                .isEqualTo("Mermelada / Dulce");
        assertThat(subtipoDe(p("Alimentos", "Miel Pura de Abeja 500g")))
                .isEqualTo("Miel / Endulzante");
    }

    @Test
    @DisplayName("a food noun that trails the protein head is a flavour, not the product")
    void elSustantivoDeComidaDetrasDeLaProteinaEsUnSabor() {
        // Every one of these matches a food subtype's keyword. None of them is food:
        // the powder names itself first and the noun is its flavour.
        for (String nombre : List.of(
                "Whey Protein sabor Dulce de Leche 1kg",
                "Proteína Whey Chocolate Chips 2lb",
                "Whey Protein Isolate sabor Granola y Miel 900g",
                "Proteína Whey sabor Café Latte 1kg",
                "Caseína sabor Avena y Canela 1kg")) {
            assertThat(subtipoDe(p("Proteína", nombre)))
                    .as("%s", nombre).isEqualTo("Proteína en Polvo");
        }
    }

    @Test
    @DisplayName("the fortified foods the powder veto pushed out now have a home")
    void losAlimentosFortificadosYaNoQuedanSinSubtipo() {
        // These were vetoed from "Proteína en Polvo" — correctly — and then landed
        // nowhere, so the builder could not offer them at all.
        assertThat(subtipoDe(p("Proteína", "Leche con Proteína Vainilla 1L")))
                .isEqualTo("Bebida Proteica");
        assertThat(subtipoDe(p("Proteína", "Avena Alta en Proteína 500g")))
                .isEqualTo("Avena / Harina");
        assertThat(subtipoDe(p("Proteína", "Galletitas rica en proteina 120g")))
                .isEqualTo("Galletas / Tostadas");
        assertThat(subtipoDe(p("Alimentos", "Yogur Proteico Frutos Rojos 200g")))
                .isEqualTo("Postre Proteico");
        assertThat(subtipoDe(p("Alimentos", "Bebida Proteica lista para tomar 330ml")))
                .isEqualTo("Bebida Proteica");
    }

    @Test
    @DisplayName("peanut butter lands in its own subtype, and whey flavoured with it does not")
    void laPastaDeManiTieneSubtipoPropioYElIsolateNoSeLoLleva() {
        // Both names are real (2026-08-11). The first used to be vetoed into nothing.
        assertThat(subtipoDe(p("Proteína",
                "ENTRENUTS Pasta De Maní Entrenuts Protein Sin TACC 370g Alta Proteína")))
                .isEqualTo("Pasta de Maní");
        assertThat(subtipoDe(p("Proteína",
                "RAW Proteína Itholate 2lb 992G - 25 Serv - CHOCOLATE PEANUT BUTTER")))
                .isEqualTo("Proteína en Polvo");
    }

    @Test
    @DisplayName("a food subtype does not steal the protein-snack subtypes that run before it")
    void losSnacksProteicosSiguenGanandoleALaComidaGenerica() {
        assertThat(subtipoDe(p("Alimentos", "GRANGER Chia Pudding 300g")))
                .isEqualTo("Snack Proteico");
        assertThat(subtipoDe(p("Alimentos", "Cookie Proteica ENA 60g")))
                .isEqualTo("Snack Proteico");
        assertThat(subtipoDe(p("Alimentos", "Barrita Proteica Maní y Chocolate")))
                .isEqualTo("Barra Proteica");
    }

    @Test
    @DisplayName("the Gym outfit combo is not grown by a food subtype")
    void elComboDelOutfitNoCreceConLaComida() {
        Set<String> deComida = SupplementCombo.tiposDisponibles().stream()
                .filter(t -> "Alimentos".equals(t.grupo()) || "Bebidas".equals(t.grupo()))
                .map(OutfitService.SupplementTipo::tipo)
                .collect(Collectors.toSet());

        assertThat(deComida).isNotEmpty();
        assertThat(SupplementCombo.TIPOS_COMBO_OUTFIT)
                .doesNotContainAnyElementsOf(deComida)
                .doesNotContain("Postre Proteico", "Mermelada / Dulce", "Miel / Endulzante")
                .contains("Proteína en Polvo", "Creatina", "Mayonesa");
    }

    @Test
    @DisplayName("the builder offers the food subtypes the outfit combo leaves out")
    void elBuilderSiOfreceLaComida() {
        List<Product> catalogo = List.of(
                p("Alimentos", "Yerba Mate Orgánica 1kg"),
                p("Alimentos", "Mix de Frutos Secos Premium 500g"));

        assertThat(combo.armarComboSuplementos(catalogo, 0, Set.of("Infusiones", "Frutos Secos")))
                .extracting(OutfitService.SupplementPick::tipo)
                .containsExactlyInAnyOrder("Infusiones", "Frutos Secos");
    }
}
