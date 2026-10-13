package ar.scraper.pages;

import com.microsoft.playwright.Page;

import java.util.List;

public class AmbiancedecoPage extends TiendanubePage {

    public AmbiancedecoPage(Page page, int timeoutMs, String sitio, String baseUrl,
                            double precioMin, double precioMax, List<String> extraUrls,
                            int maxPaginas) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls, maxPaginas);
    }

    @Override
    protected String nextPageUrl(String paginBase, int currentPage) {
        return siguientePagina(paginBase, currentPage);
    }

    static String siguientePagina(String paginBase, int currentPage) {
        return urlPagina(paginBase, currentPage + 1);
    }
}
