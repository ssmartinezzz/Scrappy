package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarcasBrowserPaginadoPerfTest {

    private static final int MARCAS = 20_000;

    @Test
    @DisplayName("a deep brand page costs about what page one does (binary-search resume, no re-sort, no skip-N)")
    void paginasProfundasNoDegradan() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < MARCAS; i++) {
            for (int j = 0; j < 2; j++) {
                ps.add(Product.builder().sitio("S").nombre("P" + i + "-" + j).precio(100 + i % 977)
                        .url("https://s/" + i + "/" + j).marca(String.format("Marca %05d", (i * 7919) % MARCAS))
                        .categoria("Remera").build());
            }
        }
        var r = new AggregatedResult(ps, Map.of("S", ps.size()), Map.of(), ResultAggregator.calcularFacets(ps), 0, 0);
        var ranking = MarcasPicksView.marcasRanking(r, null, null, "nombre");
        assertThat(ranking).hasSize(MARCAS);

        String c = MarcasPicksView.paginaMarcas(ranking, "nombre", null, 48).getNextCursor();
        for (int i = 0; i < 400; i++) c = MarcasPicksView.paginaMarcas(ranking, "nombre", c, 48).getNextCursor();
        String profundo = c;
        assertThat(profundo).isNotNull();

        long t1 = mediaNs(() -> MarcasPicksView.paginaMarcas(ranking, "nombre", null, 48));
        long t2 = mediaNs(() -> MarcasPicksView.paginaMarcas(ranking, "nombre", profundo, 48));
        System.out.printf("[PERF] /marcas-browser over %d brands: page 1 = %d ns/page, page 402 = %d ns/page%n", MARCAS, t1, t2);
        assertThat(t2).as("deep page vs page 1").isLessThan(Math.max(t1 * 20, 2_000_000));
    }

    private static long mediaNs(Runnable r) {
        for (int i = 0; i < 200; i++) r.run();
        long ini = System.nanoTime();
        for (int i = 0; i < 1000; i++) r.run();
        return (System.nanoTime() - ini) / 1000;
    }
}
