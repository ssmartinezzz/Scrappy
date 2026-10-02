package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Optional;

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
}
