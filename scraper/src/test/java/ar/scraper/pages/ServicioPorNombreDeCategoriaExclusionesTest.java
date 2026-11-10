package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping Engine")
@Feature("VTEX Parsing")
@Story("Non-product category nodes: gift cards everywhere, shopping bags on one site")
@DisplayName("ServicioPorNombreDeCategoria — gift cards and per-site shopping bags")
class ServicioPorNombreDeCategoriaExclusionesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Gift Card", "Gift Cards", "giftcards", "GiftCard", "  Gift card", "Tarjeta de regalo",
            "Tarjetas de Regalo"})
    @DisplayName("a gift card node is never a product, on any site")
    void giftCardsAreNeverProducts(String nombre) {
        assertThat(ServicioPorNombreDeCategoria.para("Easy").esServicio(nombre)).isTrue();
        assertThat(ServicioPorNombreDeCategoria.para("Sporting").esServicio(nombre)).isTrue();
        assertThat(new ServicioPorNombreDeCategoria().esServicio(nombre)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tarjetas de Árbitro", "Porta Tarjetas", "Regalos", "Mi gift", "Tarjetas gráficas"})
    @DisplayName("names that merely share a word with gift cards stay products")
    void lookAlikesStayProducts(String nombre) {
        assertThat(new ServicioPorNombreDeCategoria().esServicio(nombre)).isFalse();
    }

    @Test
    @DisplayName("the Bolsas node is excluded on El Mundo del Juguete only")
    void bolsasIsExcludedOnlyOnElMundoDelJuguete() {
        assertThat(ServicioPorNombreDeCategoria.para("Elmundodeljuguete").esServicio("Bolsas")).isTrue();
        assertThat(ServicioPorNombreDeCategoria.para("Easy").esServicio("Bolsas")).isFalse();
        assertThat(ServicioPorNombreDeCategoria.para("Sporting").esServicio("Bolsas")).isFalse();
        assertThat(new ServicioPorNombreDeCategoria().esServicio("Bolsas")).isFalse();
    }

    @Test
    @DisplayName("on El Mundo del Juguete only the exact Bolsas node goes; longer names do not")
    void bolsasIsAnExactMatch() {
        var criterio = ServicioPorNombreDeCategoria.para("Elmundodeljuguete");
        assertThat(criterio.esServicio("Bolsas de basura")).isFalse();
        assertThat(criterio.esServicio("Bolsas de Dormir y Aislantes")).isFalse();
        assertThat(criterio.esServicio("Cajas, Bolsas y Papeles")).isFalse();
    }

    @Test
    @DisplayName("the gift card subtree is flagged through inheritance, Bolsas and Outlet-like siblings are not collateral")
    void giftCardSubtreeIsInherited() {
        List<VtexPage.CategoriaVtex> arbol = List.of(
                new VtexPage.CategoriaVtex(71, "Gift Card", List.of()),
                new VtexPage.CategoriaVtex(158, "giftcards", List.of(
                        new VtexPage.CategoriaVtex(159, "ohgiftcard", List.of()))),
                new VtexPage.CategoriaVtex(151, "Bolsas", List.of()),
                new VtexPage.CategoriaVtex(26, "Peluches", List.of()));

        Set<Integer> ids = VtexPage.idsDeServicio(arbol, ServicioPorNombreDeCategoria.para("Elmundodeljuguete"));

        assertThat(ids).containsExactlyInAnyOrder(71, 158, 159, 151);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prueba", "testingids", "Prueba", " TestingIds "})
    @DisplayName("Equus: its leftover test nodes (prueba, testingids) are not products, whatever the case")
    void equusTestNodesAreExcluded(String nodo) {
        assertThat(ServicioPorNombreDeCategoria.para("Equus").esServicio(nodo)).isTrue();
    }

    @Test
    @DisplayName("the Equus test nodes are excluded on Equus only, and only as exact names")
    void equusExclusionIsPerSiteAndExact() {
        assertThat(ServicioPorNombreDeCategoria.para("Easy").esServicio("prueba")).isFalse();
        assertThat(ServicioPorNombreDeCategoria.para("Elmundodeljuguete").esServicio("testingids")).isFalse();
        assertThat(new ServicioPorNombreDeCategoria().esServicio("prueba")).isFalse();
        var equus = ServicioPorNombreDeCategoria.para("Equus");
        assertThat(equus.esServicio("Pruebas de Color")).isFalse();
        assertThat(equus.esServicio("Remeras")).isFalse();
        assertThat(equus.esServicio("Bolsas")).as("Bolsas is a Juguete-only exclusion").isFalse();
    }

    @Test
    @DisplayName("on Equus the whole prueba subtree is flagged and its siblings are not")
    void equusPruebaSubtree() {
        List<VtexPage.CategoriaVtex> arbol = List.of(
                new VtexPage.CategoriaVtex(1, "prueba", List.of(new VtexPage.CategoriaVtex(2, "testingids", List.of()))),
                new VtexPage.CategoriaVtex(3, "Remeras", List.of()));

        assertThat(VtexPage.idsDeServicio(arbol, ServicioPorNombreDeCategoria.para("Equus")))
                .containsExactlyInAnyOrder(1, 2);
    }
}
