package ar.scraper.pages;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Hadriatica's listing arrives server-rendered (12 items, images and prices in the HTML), so the
 * base scroll + 1 s wait only added ~2.8 s to each of its ~450 pages.
 */
public class HadriaticaPage extends TiendanubePage {

    public HadriaticaPage(Page page, int timeoutMs, String sitio, String baseUrl,
                          double precioMin, double precioMax, List<String> extraUrls,
                          int maxPaginas) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls, maxPaginas);
    }

    @Override
    protected void esperarListado() {
        // Nothing to wait for: see the class comment.
    }
}
