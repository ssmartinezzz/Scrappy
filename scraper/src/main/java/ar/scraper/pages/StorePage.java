package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

abstract class StorePage extends BasePage {

    protected final String sitio;
    protected final String baseUrl;
    protected final double precioMin;
    protected final double precioMax;

    protected StorePage(Page page, int timeoutMs, String sitio, String baseUrl,
                        double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio = sitio;
        this.baseUrl = baseUrl;
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    static String sinBarraFinal(String url) {
        return url.replaceAll("/+$", "");
    }

    protected boolean fueraDeRango(double precio) {
        return precio < precioMin || precio > precioMax;
    }

    protected Optional<Double> precioEnRango(String raw) {
        return parsePrecio(raw).filter(p -> !fueraDeRango(p));
    }

    protected Product producto(String nombre, double precio, Double precioOriginal, String url,
                               String imagenUrl, String categoria, String genero, List<String> talles) {
        return Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(precioOriginal)
                .url(url)
                .imagenUrl(imagenUrl)
                .categoria(categoria)
                .genero(genero)
                .talles(talles)
                .build();
    }

    static Product tecnologia(String sitio, String nombre, double precio, Double precioOriginal,
                              String url, String imagenUrl, String categoria) {
        return Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(precioOriginal)
                .url(url)
                .imagenUrl(imagenUrl)
                .categoria(categoria)
                .genero("")
                .talles(List.of())
                .rubro("tecnologia")
                .build();
    }

    /** cards[0] is whatever precedes the first card marker, not a card. */
    static List<String> cards(String html, String marcador) {
        String[] partes = html.split(marcador);
        return partes.length <= 1 ? List.of() : Arrays.asList(partes).subList(1, partes.length);
    }

    /** Stops at the first page with nothing new: some sites repeat their last page forever. */
    protected boolean crawlPaginas(int maxPaginas, String etiqueta, IntFunction<String> urlDePagina,
                                   Function<String, List<Product>> parse, Set<String> vistas,
                                   List<Product> result) {
        boolean agrego = false;
        for (int p = 1; p <= maxPaginas; p++) {
            try {
                navigateTo(urlDePagina.apply(p));
                List<Product> nuevos = parse.apply(page.content()).stream()
                        .filter(prod -> vistas.add(prod.url()))
                        .toList();
                if (nuevos.isEmpty()) break;
                result.addAll(nuevos);
                agrego = true;
            } catch (Exception e) {
                log.debug("[{}] {} p={}: {}", sitio, etiqueta, p, e.getMessage());
                break;
            }
        }
        return agrego;
    }
}
