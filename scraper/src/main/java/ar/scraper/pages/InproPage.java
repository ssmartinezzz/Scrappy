package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Los datos que sirve son los objetos crudos de la API de Tiendanube ({@code variants[]},
 * {@code compare_at_price}, {@code promotional_price}, {@code stock}, {@code sku}, imágenes en
 * {@code acdn-us.mitiendanube.com}), pero la vidriera es un Next.js propio en Vercel.
 */
public class InproPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(InproPage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sitio;
    private final String baseUrl;
    private final double precioMin;
    private final double precioMax;

    public InproPage(Page page, int timeoutMs, String sitio, String baseUrl,
                     double precioMin, double precioMax) {
        super(page, timeoutMs);
        this.sitio = sitio;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.precioMin = precioMin;
        this.precioMax = precioMax;
    }

    public List<Product> scrapeAll() {
        try {
            // Navegar al origin primero: mismo UA/cookies que el resto de los scrapers, y los fetch
            // de abajo salen same-origin.
            navigateTo(baseUrl + "/");

            String sitemap = fetchText(baseUrl + "/server-sitemap.xml");
            List<String> handles = handlesDeProducto(sitemap);
            List<String> categorias = slugsDeCategoria(sitemap);
            log.info("[{}] sitemap: {} productos, {} categorias", sitio, handles.size(), categorias.size());

            Map<String, Product> porHandle = new LinkedHashMap<>();
            // Medido contra el sitio real cuando `precio.maximo` era 300000: sin esta distinción la
            // tercera pasada pedía 38 páginas de ~550 KB (21 MB) por corrida; con ella, 6.
            Set<String> vistos = new LinkedHashSet<>();
            for (String slug : categorias) {
                Lote lote = parsear(fetchText(baseUrl + "/categorias/" + slug));
                vistos.addAll(lote.handlesVistos());
                for (Product p : lote.productos()) porHandle.put(handleDe(p.url()), p);
            }
            log.debug("[{}] {} productos ({} handles vistos) tras las {} categorias",
                    sitio, porHandle.size(), vistos.size(), categorias.size());

            List<String> faltantes = new ArrayList<>();
            for (String h : handles) if (!vistos.contains(h)) faltantes.add(h);
            if (!faltantes.isEmpty()) {
                log.info("[{}] {} handles del sitemap que ninguna categoria mostró, se piden de a uno: {}",
                        sitio, faltantes.size(), faltantes);
                for (String h : faltantes) {
                    Lote lote = parsear(fetchText(baseUrl + "/productos/" + h));
                    for (Product p : lote.productos()) porHandle.put(handleDe(p.url()), p);
                }
            }

            List<Product> result = new ArrayList<>(porHandle.values());
            log.info("[{}] COMPLETADO: {} productos", sitio, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[{}] scrapeAll error: {}", sitio, e.getMessage());
            return List.of();
        }
    }

    private String fetchText(String url) {
        try {
            Object r = page.evaluate("(u) => fetch(u).then(r => r.text())", url);
            return r == null ? "" : r.toString();
        } catch (Exception e) {
            log.debug("[{}] fetch {} fallo: {}", sitio, url, e.getMessage());
            return "";
        }
    }

    private static String handleDe(String url) {
        int i = url.lastIndexOf('/');
        return i < 0 ? url : url.substring(i + 1);
    }

    private static final Pattern LOC_PRODUCTO =
            Pattern.compile("<loc>\\s*https?://[^/\\s]+/productos/([^<\\s/]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOC_CATEGORIA =
            Pattern.compile("<loc>\\s*https?://[^/\\s]+/categorias/([^<\\s/]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    /** Los handles de producto del sitemap, ordenados y sin repetir. */
    static List<String> handlesDeProducto(String xml) {
        return extraer(LOC_PRODUCTO, xml);
    }

    static List<String> slugsDeCategoria(String xml) {
        return extraer(LOC_CATEGORIA, xml);
    }

    private static List<String> extraer(Pattern p, String xml) {
        if (StringUtils.isBlank(xml)) return List.of();
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = p.matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return new ArrayList<>(out);
    }

    // ═══════════════════════════════════════════════════════════════════════ Payload RSC (puro,
    // package-private para que el test no necesite Playwright)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Cada chunk es un STRING JS con JSON escapado adentro, y vienen partidos: un objeto de
     * producto puede empezar en un chunk y terminar en el siguiente, así que hay que concatenar
     * TODO antes de intentar leer nada.
     */
    private static final String CHUNK_INICIO = "self.__next_f.push([1,";

    /**
     * La distinción existe porque la tercera pasada de {@link #scrapeAll()} necesita saber qué
     * handle no vio NUNCA, no cuál descartó por precio.
     */
    record Lote(List<Product> productos, Set<String> handlesVistos) {}

    private Lote parsear(String html) {
        return parseLote(html, sitio, baseUrl, precioMin, precioMax);
    }

    static List<Product> parsePayload(String html, String sitio, String baseUrl,
                                      double precioMin, double precioMax) {
        return parseLote(html, sitio, baseUrl, precioMin, precioMax).productos();
    }

    static Lote parseLote(String html, String sitio, String baseUrl,
                          double precioMin, double precioMax) {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String payload = desescaparChunks(html);
        if (payload.isEmpty()) return new Lote(List.of(), Set.of());

        Map<String, Product> porHandle = new LinkedHashMap<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (String json : objetosConVariants(payload)) {
            JsonNode node;
            try {
                node = MAPPER.readTree(json);
            } catch (Exception e) {
                continue; // un objeto roto no puede tirar abajo el resto del payload
            }
            if (!node.has("name")) continue;
            String handle = textoLocalizado(node.path("handle"));
            if (handle.isEmpty()) continue;
            vistos.add(handle);
            if (porHandle.containsKey(handle)) continue;
            aProduct(node, sitio, base, precioMin, precioMax)
                    .ifPresent(p -> porHandle.put(handle, p));
        }
        return new Lote(new ArrayList<>(porHandle.values()), vistos);
    }

    private static String desescaparChunks(String html) {
        if (StringUtils.isBlank(html)) return "";
        StringBuilder sb = new StringBuilder();
        int from = 0;
        while (true) {
            int i = html.indexOf(CHUNK_INICIO, from);
            if (i < 0) break;
            int abre = html.indexOf('"', i + CHUNK_INICIO.length());
            if (abre < 0) break;
            int cierra = finDeStringJs(html, abre);
            if (cierra < 0) break;
            try {
                sb.append(MAPPER.readTree(html.substring(abre, cierra + 1)).asText());
            } catch (Exception e) {
                log.debug("chunk __next_f ilegible: {}", e.getMessage());
            }
            from = cierra + 1;
        }
        return sb.toString();
    }

    /**
     * . Índice de la comilla que CIERRA el string que abre en {@code abre}, respetando el escape.
     * Lineal y sin recursión — ver {@link #CHUNK_INICIO}..
     */
    private static int finDeStringJs(String s, int abre) {
        for (int i = abre + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * En las páginas de categoría el objeto abre con {@code "id"}, pero en las de producto abre con
     * {@code "name"} y el {@code "id"} aparece recién después de {@code "variants"} (confirmado
     * contra {@code /productos/pod-meet}).
     */
    private static List<String> objetosConVariants(String s) {
        List<String> out = new ArrayList<>();
        Deque<Integer> pila = new ArrayDeque<>();
        Set<Integer> candidatos = new java.util.HashSet<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '"') {
                    j += (s.charAt(j) == '\\') ? 2 : 1;
                }
                if (j < n && !pila.isEmpty() && esClave(s, i + 1, j, "variants")) {
                    int k = j + 1;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    if (k < n && s.charAt(k) == ':') candidatos.add(pila.peek());
                }
                i = j + 1;
                continue;
            }
            if (c == '{') {
                pila.push(i);
            } else if (c == '}') {
                if (!pila.isEmpty()) {
                    int abre = pila.pop();
                    if (candidatos.remove(abre)) out.add(s.substring(abre, i + 1));
                }
            }
            i++;
        }
        return out;
    }

    private static boolean esClave(String s, int desde, int hasta, String clave) {
        return (hasta - desde) == clave.length() && s.regionMatches(desde, clave, 0, clave.length());
    }

    private static Optional<Product> aProduct(JsonNode n, String sitio, String base,
                                              double precioMin, double precioMax) {
        String nombre = textoLocalizado(n.path("name"));
        String handle = textoLocalizado(n.path("handle"));
        if (nombre.isBlank() || handle.isBlank()) return Optional.empty();

        JsonNode variante = mejorVariante(n.path("variants"));
        if (variante == null) return Optional.empty();

        double precio = precioEfectivo(variante);
        if (precio <= 0 || precio < precioMin || precio > precioMax) return Optional.empty();

        Double precioOriginal = null;
        Double compare = aDouble(variante.path("compare_at_price"));
        // compare_at_price == price en TODO el catálogo sin descuento.
        if (compare != null && compare > precio) precioOriginal = compare;

        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                base + "/productos/" + handle,
                primeraImagen(n.path("images")),
                categoriaCruda(n.path("categories")),
                "",
                List.of(),
                Product.MlScore.EMPTY,
                "",
                "oficina",
                false));
    }

    private static JsonNode mejorVariante(JsonNode variants) {
        if (!variants.isArray() || variants.isEmpty()) return null;
        JsonNode mejor = null;
        double mejorPrecio = Double.MAX_VALUE;
        boolean mejorConStock = false;
        for (JsonNode v : variants) {
            double precio = precioEfectivo(v);
            if (precio <= 0) continue;
            boolean conStock = v.path("stock").asInt(0) > 0;
            boolean gana = (mejor == null)
                    || (conStock && !mejorConStock)
                    || (conStock == mejorConStock && precio < mejorPrecio);
            if (gana) {
                mejor = v;
                mejorPrecio = precio;
                mejorConStock = conStock;
            }
        }
        return mejor;
    }

    /** {@code promotional_price} si hay descuento vigente; si no, {@code price}. */
    private static double precioEfectivo(JsonNode v) {
        Double promo = aDouble(v.path("promotional_price"));
        if (promo != null && promo > 0) return promo;
        Double precio = aDouble(v.path("price"));
        return precio == null ? 0 : precio;
    }

    private static Double aDouble(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        try {
            String s = n.asText("").trim();
            return s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textoLocalizado(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        if (n.isTextual()) return n.asText().trim();
        String es = n.path("es").asText("").trim();
        return es.isEmpty() ? n.path("value").asText("").trim() : es;
    }

    private static String primeraImagen(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) return "";
        JsonNode primera = null;
        int min = Integer.MAX_VALUE;
        for (JsonNode img : images) {
            int pos = img.path("position").asInt(Integer.MAX_VALUE);
            if (pos < min) { min = pos; primera = img; }
        }
        return primera == null ? "" : primera.path("src").asText("").trim();
    }

    /** Es sólo una pista: */
    private static String categoriaCruda(JsonNode categories) {
        if (!categories.isArray() || categories.isEmpty()) return "";
        Set<String> nombres = new LinkedHashSet<>();
        for (JsonNode c : categories) {
            String nombre = textoLocalizado(c.path("name"));
            if (!nombre.isBlank()) nombres.add(nombre);
        }
        if (nombres.isEmpty()) return "";
        List<String> lista = new ArrayList<>(nombres);
        return lista.get(lista.size() - 1);
    }
}
