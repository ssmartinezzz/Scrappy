package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("search_products — BM25F relevance")
@DisplayName("RelevanceRanker")
class RelevanceRankerTest {

    private final RelevanceRanker ranker = new RelevanceRanker();

    @Test
    @DisplayName("exact word > prefix > typo, all else equal")
    void exactBeatsPrefixBeatsTypo() {
        var r = snapshot(
                p("SSD Kingston", ""),
                p("SSD Kingstonia", ""),
                p("SSD Kingstom", ""),
                p("Mouse Gamer", ""));

        var s = ranker.score(r, List.of("kingston"));

        assertThat(s.score()[0]).isGreaterThan(s.score()[1]);
        assertThat(s.score()[1]).isGreaterThan(s.score()[2]);
        assertThat(s.score()[2]).isGreaterThan(0);
        assertThat(s.score()[3]).isZero();
        assertThat(s.mask()[0]).isEqualTo(1);
        assertThat(s.mask()[3]).isZero();
    }

    @Test
    @DisplayName("a brand hit weighs more than the same word in the name")
    void marcaBeatsNombre() {
        var r = snapshot(
                p("Pendrive Sandisk", "Generica"),
                p("Pendrive Generica", "Sandisk"),
                p("Mouse Gamer", "Otra"),
                p("Teclado Gamer", "Otra"));

        var s = ranker.score(r, List.of("sandisk"));

        assertThat(s.score()[1]).isGreaterThan(s.score()[0]);
    }

    @Test
    @DisplayName("IDF: the rarer token weighs more")
    void rarerTokenWeighsMore() {
        var r = snapshot(
                p("SSD Generico", ""),
                p("Hiksemi Generico", ""),
                p("SSD Generico", ""),
                p("SSD Generico", ""),
                p("SSD Generico", ""),
                p("Mouse Generico", ""));

        var s = ranker.score(r, List.of("ssd", "hiksemi"));

        assertThat(s.score()[1]).isGreaterThan(s.score()[0]);
        assertThat(s.mask()[0]).isEqualTo(0b01);
        assertThat(s.mask()[1]).isEqualTo(0b10);
    }

    @Test
    @DisplayName("a unit token matches digits+unit words (tb ~ 1tb) but not longer words")
    void unitSuffix() {
        var r = snapshot(p("SSD 1TB", ""), p("SSD 500GB", ""), p("SSD 1TBX", ""));

        var s = ranker.score(r, List.of("tb"));

        assertThat(s.score()[0]).isGreaterThan(0);
        assertThat(s.score()[1]).isZero();
        assertThat(s.score()[2]).isZero();
    }

    @Test
    @DisplayName("short tokens never match inside longer words (the space is the word boundary)")
    void noRawSubstring() {
        var r = snapshot(p("Programa Nutricional", ""), p("Frambuesa Barra", ""),
                p("Memoria RAM DDR4", ""));

        var s = ranker.score(r, List.of("ram"));

        assertThat(s.score()[0]).isZero();
        assertThat(s.score()[1]).isZero();
        assertThat(s.score()[2]).isGreaterThan(0);
    }

    @Test
    @DisplayName("typos need an alphabetic token of length >= 5; model numbers are not fuzzy")
    void typoRules() {
        var r = snapshot(p("Placa RTX3070", ""), p("Caja Kingston", ""), p("Otro Producto", ""));

        assertThat(ranker.score(r, List.of("rtx3060")).score()[0]).isZero();
        assertThat(ranker.score(r, List.of("kingstom")).score()[1]).isGreaterThan(0);
        // transposition counts as one edit (Damerau)
        assertThat(ranker.score(r, List.of("kignston")).score()[1]).isGreaterThan(0);
        // length 4: no typo tolerance
        assertThat(ranker.score(r, List.of("caza")).score()[1]).isZero();
    }

    @Test
    @DisplayName("category and subCategory are searchable fields")
    void categoriaIsAField() {
        var r = snapshot(pc("Producto X", "", "Zapatilla", "Running"), p("Otro", ""));

        assertThat(ranker.score(r, List.of("zapatilla")).score()[0]).isGreaterThan(0);
        assertThat(ranker.score(r, List.of("running")).score()[0]).isGreaterThan(0);
    }

    @Test
    @DisplayName("the index is cached per snapshot instance and rebuilt for a new one")
    void indexIsCachedPerSnapshot() {
        var r1 = snapshot(p("SSD Kingston", ""));
        var idx = ranker.indexFor(r1);

        assertThat(ranker.indexFor(r1)).isSameAs(idx);
        ranker.score(r1, List.of("ssd"));
        assertThat(ranker.indexFor(r1)).isSameAs(idx);

        var r2 = snapshot(p("SSD Kingston", ""));
        assertThat(ranker.indexFor(r2)).isNotSameAs(idx);
    }

    @Test
    @DisplayName("empty catalog and empty token list are harmless")
    void degenerate() {
        assertThat(ranker.score(snapshot(), List.of("ssd")).score()).isEmpty();
        assertThat(ranker.score(snapshot(p("SSD", "")), List.of()).score()[0]).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static AggregatedResult snapshot(Product... ps) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(List.of(ps), Map.of(), Map.of(), facets, 0, 0);
    }

    private static Product p(String nombre, String marca) {
        return pc(nombre, marca, "", "");
    }

    private static Product pc(String nombre, String marca, String categoria, String sub) {
        return new Product("Sitio", nombre, 100, null, "https://x.com/" + nombre.hashCode(), "img",
                categoria, "unisex", List.of(), Product.MlScore.EMPTY, marca,
                "tecnologia", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, sub);
    }
}
