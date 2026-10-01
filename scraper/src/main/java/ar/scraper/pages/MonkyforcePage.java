package ar.scraper.pages;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * El selector genérico {@code h1,h2,h3,h4}-primero del extractor base agarraba el {@code } "Sin
 * stock" como nombre → todos los productos salían "Sin stock" → el dedup por {@code sitio+nombre}
 * en el aggregator colapsaba la tienda entera a UN solo producto.
 */
public class MonkyforcePage extends TiendanubePage {

    public MonkyforcePage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax, List<String> extraUrls) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls);
    }

    public MonkyforcePage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax, List<String> extraUrls,
                          int maxPaginas) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls, maxPaginas);
    }

    @Override
    protected String nombreSelectorJs() {
        // Deliberadamente SIN h4 (es el overlay "Sin stock"). a[title] y h1-h3 quedan como
        // fallbacks defensivos.
        return "el.querySelector('.js-item-name,.item-name,[class*=item-name],[class*=product-name]')"
             + "||el.querySelector('a[title]')"
             + "||el.querySelector('h1,h2,h3')";
    }
}
