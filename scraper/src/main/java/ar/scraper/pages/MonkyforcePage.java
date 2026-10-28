package ar.scraper.pages;

import com.microsoft.playwright.Page;

import ar.scraper.model.Product;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * El selector genérico {@code h1,h2,h3,h4}-primero del extractor base agarraba el {@code } "Sin
 * stock" como nombre → todos los productos salían "Sin stock" → el dedup por {@code sitio+nombre}
 * en el aggregator colapsaba la tienda entera a UN solo producto.
 */
public class MonkyforcePage extends TiendanubePage {

    private record Coleccion(String path, String genero, String categoria) {}

    private static final List<Coleccion> COLECCIONES = List.of(
            new Coleccion("/indumentaria/remeras/", "hombre", "Remera"),
            new Coleccion("/indumentaria/musculosa/", "hombre", "Musculosa"),
            new Coleccion("/indumentaria/short/", "hombre", "Short"),
            new Coleccion("/indumentaria/buzos-y-camperas1/", "hombre", "Buzo"),
            new Coleccion("/indumentaria/pantalones1/", "hombre", "Pantalón"),
            new Coleccion("/indumentaria/primeras-piel1/", "hombre", "Remera"),
            new Coleccion("/indumentaria/accesorios/", "hombre", ""),
            new Coleccion("/indumentaria-mujer/remeras-top/", "mujer", "Remera"),
            new Coleccion("/indumentaria-mujer/shorts1/", "mujer", ""),
            new Coleccion("/indumentaria-mujer/buzos-y-camperas/", "mujer", "Buzo"),
            new Coleccion("/indumentaria-mujer/pantalones2/", "mujer", "Pantalón"),
            new Coleccion("/indumentaria-mujer/accesorios1/", "mujer", ""));

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

    @Override
    public List<Product> scrapeAll() {
        String origen = domain(baseUrl);
        List<Product> result = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (Coleccion c : COLECCIONES) {
            for (Product pr : scrapeJs(origen + c.path())) {
                if (StringUtils.isBlank(pr.url()) || vistas.add(pr.url())) result.add(conHint(pr, c));
            }
        }
        for (Product pr : super.scrapeAll()) {
            if (StringUtils.isBlank(pr.url()) || vistas.add(pr.url())) result.add(pr);
        }
        return result;
    }

    private static Product conHint(Product pr, Coleccion c) {
        return pr.toBuilder()
                .genero(StringUtils.defaultIfBlank(pr.genero(), c.genero()))
                .categoria(StringUtils.defaultIfBlank(pr.categoria(), c.categoria()))
                .build();
    }
}
