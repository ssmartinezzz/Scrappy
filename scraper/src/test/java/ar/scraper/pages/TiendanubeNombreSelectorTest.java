package ar.scraper.pages;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the product-name resolution seam {@code nombreSelectorJs()}.
 *
 * <p>Extension point (Template Method): the base TN page returns the generic,
 * theme-agnostic selector; {@link MonkyforcePage} specializes it WITHOUT
 * touching the shared extractor. Monkyforce renders an out-of-stock overlay in
 * an {@code <h4 class="overlay-no-stock-text">Sin stock</h4>} on every card;
 * the base {@code h1,h2,h3,h4}-first selector grabbed it as the product name,
 * so every product came out named "Sin stock" and dedup by site+name collapsed
 * the whole store to a single product.</p>
 */
class TiendanubeNombreSelectorTest {

    @Test
    void baseUsaHeadingsGenericos() {
        TiendanubePage base = new TiendanubePage(null, 0, "x", "https://x.com", 0, 1);
        assertThat(base.nombreSelectorJs()).contains("h1,h2,h3,h4");
    }

    @Test
    void monkyforcePriorizaItemNameYNoUsaH4() {
        MonkyforcePage mf = new MonkyforcePage(null, 0, "Monkyforce",
                "https://www.monkyforce.com/productos/", 0, 1, java.util.List.of());
        assertThat(mf.nombreSelectorJs()).contains("js-item-name");
        assertThat(mf.nombreSelectorJs()).doesNotContain("h4");
    }
}
