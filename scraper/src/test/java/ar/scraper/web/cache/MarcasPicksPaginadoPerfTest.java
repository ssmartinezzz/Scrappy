package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarcasPicksPaginadoPerfTest {

    private static Product p(int i) {
        return Product.builder()
                .sitio("Sitio").nombre("P" + i).precio(1000).url("https://s/r" + String.format("%06d", i)).imagenUrl("img")
                .categoria("Remera").genero("unisex").talles(List.of())
                .ml(new MlScore(1 + (i % 90), "", false, "estable", 1))
                .marca("Marca").rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).subCategoria("").build();
    }

    @Test
    @DisplayName("a deep page costs about what page one does (binary-search resume, no re-sort, no skip-N)")
    void paginasProfundasNoDegradan() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 120_000; i++) ps.add(p(i));
        var r = new AggregatedResult(ps, Map.of("Sitio", ps.size()), Map.of(), ResultAggregator.calcularFacets(ps), 0, 0);
        var ranking = MarcasPicksView.ranking(r, null, false, "").get("Remera");
        assertThat(ranking).hasSize(120_000);

        String c = MarcasPicksView.pagina(ranking, null, 24).getNextCursor();
        for (int i = 0; i < 4_900; i++) c = MarcasPicksView.pagina(ranking, c, 24).getNextCursor();
        String profundo = c;
        assertThat(profundo).isNotNull();

        long t1 = mediaNs(() -> MarcasPicksView.pagina(ranking, null, 24));
        long t2 = mediaNs(() -> MarcasPicksView.pagina(ranking, profundo, 24));
        System.out.printf("[PERF] /mejores/picks over 120000 products: page 1 = %d ns/page, page 4900 = %d ns/page%n", t1, t2);
        assertThat(t2).as("deep page vs page 1").isLessThan(Math.max(t1 * 20, 2_000_000));
    }

    private static long mediaNs(Runnable r) {
        for (int i = 0; i < 200; i++) r.run();
        long ini = System.nanoTime();
        for (int i = 0; i < 1000; i++) r.run();
        return (System.nanoTime() - ini) / 1000;
    }
}
