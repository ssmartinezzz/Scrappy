package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Scraper para tiendas WooCommerce (WordPress + WooCommerce).
 *
 * Indicadores WC: li.product, woocommerce-Price-amount, wp-post-image,
 *                 woocommerce-loop-product__title
 *
 * Formato de precio soportado:
 *   "ARS209 175"   → 209175
 *   "$209.175,00"  → 209175
 *   "209175"       → 209175
 */
public class WooCommercePage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(WooCommercePage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGES = 50;

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public WooCommercePage(Page page, int timeoutMs,
                           String sitio, String baseUrl,
                           double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio     = sitio;
        this.baseUrl   = baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public List<Product> scrapeAll() {
        List<Product> result = new ArrayList<>();
        Set<String> urlsVistas = new HashSet<>();

        // Intentar detectar la URL de la tienda
        String shopUrl = detectarShopUrl();
        if (shopUrl == null) {
            log.warn("[{}] No se encontró URL de tienda WooCommerce", sitio);
            return result;
        }
        log.info("[{}] Shop URL detectada: {}", sitio, shopUrl);

        for (int p = 1; p <= MAX_PAGES; p++) {
            String pageUrl = (p == 1) ? shopUrl : shopUrl + "page/" + p + "/";
            log.debug("[{}] page {}: {}", sitio, p, pageUrl);

            try {
                navigateTo(pageUrl);
                page.waitForTimeout(800);

                String json = (String) page.evaluate(buildExtractorJs());
                if (json == null || json.equals("[]")) {
                    log.info("[{}] página {} vacía → fin", sitio, p);
                    break;
                }

                JsonNode arr = MAPPER.readTree(json);
                if (!arr.isArray() || arr.isEmpty()) break;

                int antes = result.size();
                Set<String> urlsPagina = new HashSet<>();
                for (JsonNode n : arr) {
                    urlsPagina.add(n.path("url").asText(""));
                    fromNode(n).ifPresent(result::add);
                }

                // Detectar página repetida
                if (!urlsPagina.isEmpty() && urlsVistas.containsAll(urlsPagina)) {
                    log.info("[{}] página {} repetida → fin de catálogo", sitio, p);
                    break;
                }
                urlsVistas.addAll(urlsPagina);

                log.info("[{}] p{}: +{} (total {})", sitio, p,
                        result.size() - antes, result.size());

                if (arr.size() < 6) break;

            } catch (Exception e) {
                log.warn("[{}] error p{}: {}", sitio, p, e.getMessage());
                break;
            }
        }

        log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
        return result;
    }

    // ─── Detectar URL de la tienda ────────────────────────────────────────────

    private String detectarShopUrl() {
        // Candidatas comunes para WooCommerce en Argentina
        String[] candidatas = {
            baseUrl + "/tienda/",
            baseUrl + "/shop/",
            baseUrl + "/productos/",
            baseUrl + "/store/",
            baseUrl + "/"
        };

        for (String url : candidatas) {
            try {
                navigateTo(url);
                page.waitForTimeout(600);

                Boolean tieneProductos = (Boolean) page.evaluate(
                    "document.querySelector('li.product, .woocommerce-loop-product__title," +
                    " ul.products, .products.columns') !== null"
                );
                if (Boolean.TRUE.equals(tieneProductos)) {
                    // Normalizar: asegurar que termina en /
                    return url.endsWith("/") ? url : url + "/";
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─── Modelo ───────────────────────────────────────────────────────────────

    private Optional<Product> fromNode(JsonNode n) {
        try {
            String nombre = n.path("nombre").asText("").trim();
            if (nombre.isBlank()) return Optional.empty();

            String priceStr  = n.path("precio").asText("");
            String origStr   = n.path("precioOrig").asText("");
            String url       = n.path("url").asText("");
            String img       = n.path("img").asText("");
            String genero    = normalizarGenero(n.path("genero").asText(""));
            String categoria = n.path("categoria").asText("");

            Optional<Double> precio = parsePrecioWC(priceStr);
            if (precio.isEmpty() || precio.get() < precioMin || precio.get() > precioMax)
                return Optional.empty();

            if (img.startsWith("//")) img = "https:" + img;

            String precioOrig = null;
            if (!origStr.isBlank()) {
                Optional<Double> pOrig = parsePrecioWC(origStr);
                if (pOrig.isPresent() && pOrig.get() > precio.get())
                    precioOrig = origStr;
            }

            return Optional.of(new Product(
                    sitio, nombre, precio.get(), precioOrig,
                    url, img, categoria, genero, List.of()));

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parser de precio WooCommerce.
     * Soporta: "ARS209 175", "$209.175,00", "209175", "ARS 209.175"
     */
    private Optional<Double> parsePrecioWC(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        // Quitar prefijo moneda (ARS, $, USD, etc.) y espacios
        String s = raw.replaceAll("[A-Za-z$€£]", "").trim();
        // Quitar espacios usados como separador de miles (formato ARS "209 175")
        s = s.replaceAll("\\s+", "");
        // Formato argentino: punto=miles, coma=decimal → "209.175,00"
        if (s.matches(".*[.,]\\d{2}$")) {
            // Si termina con ,XX o .XX → decimal
            char dec = s.charAt(s.length() - 3);
            if (dec == ',') {
                s = s.replace(".", "").replace(",", ".");
            } else if (dec == '.') {
                // Podría ser separador de miles, no decimal
                if (s.lastIndexOf('.') == s.indexOf('.')) {
                    // Solo hay un punto → es decimal
                    s = s.replace(",", "");
                } else {
                    s = s.replace(".", "").replace(",", ".");
                }
            }
        } else {
            // Sin decimales → quitar todos los separadores
            s = s.replace(".", "").replace(",", "");
        }
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String normalizarGenero(String raw) {
        if (raw == null) return "";
        String r = raw.trim().toLowerCase();
        if (r.contains("hombre") || r.contains("men")   || r.equals("male"))   return "hombre";
        if (r.contains("mujer")  || r.contains("women") || r.equals("female")) return "mujer";
        if (r.contains("unisex"))                                               return "unisex";
        return "";
    }

    // ─── Extractor JS ─────────────────────────────────────────────────────────

    private String buildExtractorJs() {
        return "(function() {" +
            "var results = [];" +
            "var seen = new Set();" +

            // Selectores WooCommerce para cards de producto
            "var cards = Array.from(document.querySelectorAll(" +
            "  'li.product, .product-item, .wc-block-grid__product, " +
            "   article.product-type-simple, article.product-type-variable'));" +

            "cards.forEach(function(card) {" +
            "  try {" +

            // URL y nombre: el link principal del producto
            "    var linkEl = card.querySelector('a.woocommerce-loop-product__link, " +
            "      a.wp-block-button__link, a[href*=\"/product/\"], " +
            "      h2 a, h3 a, .woocommerce-loop-product__title a');" +
            "    if (!linkEl) linkEl = card.querySelector('a[href]');" +
            "    if (!linkEl) return;" +
            "    var url = linkEl.href || '';" +
            "    if (!url || seen.has(url)) return; seen.add(url);" +

            // Nombre
            "    var nameEl = card.querySelector('.woocommerce-loop-product__title, " +
            "      h2, h3, .wc-block-grid__product-title, .product-title');" +
            "    var nombre = nameEl ? nameEl.textContent.trim() : linkEl.textContent.trim();" +
            "    if (!nombre || nombre.length < 2) return;" +

            // Imagen
            "    var img = '';" +
            "    var imgEl = card.querySelector('img.wp-post-image, img.woocommerce-placeholder, " +
            "      .wc-block-grid__product-image img, img[class*=product], img');" +
            "    if (imgEl) {" +
            "      var attrs = ['data-src','data-lazy-src','data-zoom-src','src'];" +
            "      for (var k = 0; k < attrs.length; k++) {" +
            "        var v = imgEl.getAttribute(attrs[k]) || '';" +
            "        if (v && v.length > 10 && v.indexOf('data:') < 0 && " +
            "            v.indexOf('placeholder') < 0) { img = v; break; }" +
            "      }" +
            "    }" +

            // Precio: WC pone precio original en <del> y actual en <ins>
            // Si no hay oferta, el precio está directamente en .price
            "    var precio = '';" +
            "    var precioOrig = '';" +
            "    var insEl = card.querySelector('ins .woocommerce-Price-amount bdi, " +
            "      ins .woocommerce-Price-amount, .price ins bdi, .price ins');" +
            "    var delEl = card.querySelector('del .woocommerce-Price-amount bdi, " +
            "      del .woocommerce-Price-amount, .price del bdi, .price del');" +
            "    var singleEl = card.querySelector('.woocommerce-Price-amount bdi, " +
            "      .woocommerce-Price-amount, .price .amount, .price bdi, " +
            "      .wc-block-grid__product-price bdi');" +
            "    if (insEl) {" +
            "      precio = insEl.textContent.trim();" +
            "      if (delEl) precioOrig = delEl.textContent.trim();" +
            "    } else if (singleEl) {" +
            "      precio = singleEl.textContent.trim();" +
            "    }" +
            "    if (!precio) return;" +

            // Categoría desde breadcrumb o atributo
            "    var categoria = '';" +
            "    var catEl = card.querySelector('[class*=cat], [data-category], " +
            "      .product-category, .posted_in a');" +
            "    if (catEl) categoria = catEl.textContent.trim();" +

            "    results.push({" +
            "      nombre: nombre, precio: precio, precioOrig: precioOrig," +
            "      url: url, img: img, categoria: categoria, genero: ''" +
            "    });" +
            "  } catch(e) {}" +
            "});" +

            "return JSON.stringify(results);" +
            "})()";
    }
}
