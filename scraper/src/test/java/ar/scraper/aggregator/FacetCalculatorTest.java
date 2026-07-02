package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FacetCalculator}, extracted verbatim from
 * {@code ResultAggregator.calcularFacets}/{@code sortTalles} (Work Unit 9 of
 * the aggregator SOLID modularization) — pure relocation, no behavior change.
 */
class FacetCalculatorTest {

    private Product product(String nombre, double precio, String categoria, String genero,
                             List<String> talles, String marca, Product.MlScore ml, String subCategoria) {
        return new Product("TestSite", nombre, precio, null, "http://test.com/" + nombre.hashCode(),
                "", categoria, genero, talles, ml, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, subCategoria);
    }

    @Test
    void talles_areSortedKnownSizesFirstThenNumericThenAlphabetical() {
        Product p1 = product("A", 100, "Remeras", "", List.of("L", "10", "S", "XL"), "", Product.MlScore.EMPTY, "");
        Product p2 = product("B", 200, "Remeras", "", List.of("42", "M"), "", Product.MlScore.EMPTY, "");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(p1, p2));

        assertThat(facets.talles().keySet())
                .containsExactly("S", "M", "L", "XL", "10", "42");
    }

    @Test
    void generos_countsNonBlankLowercasedValues() {
        Product hombre1 = product("A", 100, "Remeras", "Hombre", List.of(), "", Product.MlScore.EMPTY, "");
        Product hombre2 = product("B", 200, "Remeras", "hombre", List.of(), "", Product.MlScore.EMPTY, "");
        Product sinGenero = product("C", 300, "Remeras", "", List.of(), "", Product.MlScore.EMPTY, "");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(hombre1, hombre2, sinGenero));

        assertThat(facets.generos()).containsExactly(Map.entry("hombre", 2L));
    }

    @Test
    void categorias_normalizedToTitleCaseAndSortedByCountDescending() {
        Product a1 = product("A", 100, "remeras", "", List.of(), "", Product.MlScore.EMPTY, "");
        Product a2 = product("B", 200, "REMERAS", "", List.of(), "", Product.MlScore.EMPTY, "");
        Product b1 = product("C", 300, "zapatillas", "", List.of(), "", Product.MlScore.EMPTY, "");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(a1, a2, b1));

        assertThat(facets.categorias().keySet()).containsExactly("Remeras", "Zapatillas");
        assertThat(facets.categorias().get("Remeras")).isEqualTo(2L);
    }

    @Test
    void marcas_sortedByCountDescending() {
        Product nike1 = product("A", 100, "Remeras", "", List.of(), "Nike", Product.MlScore.EMPTY, "");
        Product nike2 = product("B", 200, "Remeras", "", List.of(), "Nike", Product.MlScore.EMPTY, "");
        Product adidas = product("C", 300, "Remeras", "", List.of(), "Adidas", Product.MlScore.EMPTY, "");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(nike1, nike2, adidas));

        assertThat(facets.marcas().keySet()).containsExactly("Nike", "Adidas");
    }

    @Test
    void badges_countedFromMlScoreBadge() {
        Product oferta = product("A", 100, "Remeras", "", List.of(), "",
                new Product.MlScore(80, "oferta_real", true, "estable", 20), "");
        Product sinBadge = product("B", 200, "Remeras", "", List.of(), "", Product.MlScore.EMPTY, "");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(oferta, sinBadge));

        assertThat(facets.badges()).containsExactly(Map.entry("oferta_real", 1L));
    }

    @Test
    void subCategorias_sortedAlphabetically() {
        Product running = product("A", 100, "Remeras", "", List.of(), "", Product.MlScore.EMPTY, "running");
        Product crossfit = product("B", 200, "Remeras", "", List.of(), "", Product.MlScore.EMPTY, "crossfit");

        ResultAggregator.Facets facets = FacetCalculator.calcular(List.of(running, crossfit));

        assertThat(facets.subCategorias().keySet()).containsExactly("crossfit", "running");
    }
}
