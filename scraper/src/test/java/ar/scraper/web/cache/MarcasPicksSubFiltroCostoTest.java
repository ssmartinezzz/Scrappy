package ar.scraper.web.cache;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-request sub filter is one O(n) pass over a single category's ranking; this pins that it stays cheap. */
class MarcasPicksSubFiltroCostoTest {

    @Test
    void filtraUnaCategoriaGrandeEnMilisegundos() {
        List<MarcasPicksView.Ranked> ranking = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            Product p = Product.builder().sitio("S").precio(1).nombre("p" + i).url("https://s/" + i).subCategoria(i % 50 == 0 ? "hdd" : "ssd").build();
            ranking.add(new MarcasPicksView.Ranked(i, p.url(), p));
        }
        assertThat(MarcasPicksView.filtrarPorSub(ranking, " hdd ")).hasSize(100);
        assertThat(MarcasPicksView.filtrarPorSub(ranking, "")).isSameAs(ranking);
        for (int i = 0; i < 200; i++) MarcasPicksView.filtrarPorSub(ranking, "hdd");
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000; i++) MarcasPicksView.filtrarPorSub(ranking, "hdd");
        long us = (System.nanoTime() - t0) / 1_000 / 1_000;
        System.out.println("PICKS-SUB-FILTER n=5000 per-request(us)=" + us);
        assertThat(us).as("microseconds per filter pass").isLessThan(2_000);
    }
}
