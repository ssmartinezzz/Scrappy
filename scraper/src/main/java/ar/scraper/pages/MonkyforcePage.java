package ar.scraper.pages;

import com.microsoft.playwright.Page;

/**
 * Monkyforce-specific Tiendanube page.
 *
 * <p>El tema de Monkyforce renderiza un overlay de stock
 * {@code <h4 class="overlay-no-stock-text">Sin stock</h4>} en CADA card
 * (incluso las que tienen stock), y expone el nombre del producto en
 * {@code <a class="js-item-name item-name h5">}. El selector genérico
 * {@code h1,h2,h3,h4}-primero del extractor base agarraba el {@code <h4>}
 * "Sin stock" como nombre → todos los productos salían "Sin stock" → el dedup
 * por {@code sitio+nombre} en el aggregator colapsaba la tienda entera a UN
 * solo producto.</p>
 *
 * <p>Hereda todo el extractor de {@link TiendanubePage} y solo especializa el
 * seam {@link #nombreSelectorJs()} (Open/Closed): no reescribe ni duplica la
 * lógica compartida, y no afecta a ningún otro sitio TN.</p>
 */
public class MonkyforcePage extends TiendanubePage {

    public MonkyforcePage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax);
    }

    @Override
    protected String nombreSelectorJs() {
        // Priorizar el hook de nombre del tema; el textContent del <a> js-item-name
        // es el nombre real del producto. Deliberadamente SIN h4 (es el overlay
        // "Sin stock"). a[title] y h1-h3 quedan como fallbacks defensivos.
        return "el.querySelector('.js-item-name,.item-name,[class*=item-name],[class*=product-name]')"
             + "||el.querySelector('a[title]')"
             + "||el.querySelector('h1,h2,h3')";
    }
}
