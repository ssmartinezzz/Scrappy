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

/**
 * INPRO ({@code inpro.ar}) — mobiliario de oficina.
 *
 * <p><b>Es un Tiendanube headless.</b> Los datos que sirve son los objetos
 * crudos de la API de Tiendanube ({@code variants[]}, {@code compare_at_price},
 * {@code promotional_price}, {@code stock}, {@code sku}, imágenes en
 * {@code acdn-us.mitiendanube.com}), pero la vidriera es un Next.js propio en
 * Vercel. El storefront clásico NO es alcanzable —{@code inpro.mitiendanube.com}
 * redirige a otra tienda, {@code inproindumentaria.com.ar}, y los slugs
 * candidatos dan 410— así que {@link TiendanubePage} no tiene dónde apuntar:
 * iría a buscar un DOM que en {@code inpro.ar} no existe.</p>
 *
 * <p>Lo que sí existe es el payload RSC que Next.js embebe en cada página, en
 * chunks {@code self.__next_f.push([1,"<json escapado>"])}. Leer eso es
 * estrictamente mejor que scrapear el DOM renderizado: trae precio de lista,
 * precio promocional, precio comparado, stock por variante y SKU, que es más de
 * lo que la vidriera muestra.</p>
 *
 * <h2>Enumeración: tres pasadas, y las tres están medidas</h2>
 * <p>Números de una corrida real contra el sitio en vivo (2026-08-20):</p>
 * <ol>
 *   <li>{@code /server-sitemap.xml} — 106 productos y 16 categorías. Es el
 *       camino que {@code robots.txt} habilita: {@code /api/*} está
 *       explícitamente en {@code Disallow}, el sitemap está en {@code Sitemap:}.</li>
 *   <li>Las páginas de categoría, que traen el objeto completo de cada
 *       producto: <b>100 productos en 16 fetches</b> en vez de 106.</li>
 *   <li>Los handles del sitemap que ninguna categoría mostró — <b>6</b>, porque
 *       {@code /categorias/pods} devuelve 0 productos. De esos 6 se recupera
 *       <b>1</b> ({@code reposapies-stepsync}); los cinco {@code pod-*} son
 *       cabinas acústicas con {@code price: null}, que se venden a consultar y
 *       quedan afuera con razón.</li>
 * </ol>
 * <p><b>Total: 101 productos</b>, 0 sin imagen, 5 con descuento vigente.</p>
 *
 * <p>Dos bugs de este parser sólo aparecieron corriéndolo contra el sitio en
 * vivo, y ninguno de los dos habría fallado un test de fixture escrito a mano:
 * el ancla {@code &#123;"id":} no matchea en las páginas de producto (ver
 * {@link #objetosConVariants}), y el regex de chunks tiraba
 * {@code StackOverflowError} (ver {@link #CHUNK_INICIO}). Es el mismo tipo de
 * agujero que {@code OsCommercePage} documenta: verde en test, cero en
 * producción.</p>
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

    // ═══════════════════════════════════════════════════════════════════════
    // Orquestación
    // ═══════════════════════════════════════════════════════════════════════

    public List<Product> scrapeAll() {
        try {
            // Navegar al origin primero: mismo UA/cookies que el resto de los
            // scrapers, y los fetch de abajo salen same-origin.
            navigateTo(baseUrl + "/");

            String sitemap = fetchText(baseUrl + "/server-sitemap.xml");
            List<String> handles = handlesDeProducto(sitemap);
            List<String> categorias = slugsDeCategoria(sitemap);
            log.info("[{}] sitemap: {} productos, {} categorias", sitio, handles.size(), categorias.size());

            // Por handle, no por id: es la clave con la que el sitemap enumera
            // y con la que se arma la URL, así que es la que permite cerrar el
            // diff de la tercera pasada.
            Map<String, Product> porHandle = new LinkedHashMap<>();
            // VISTO != ACEPTADO, y la diferencia cuesta plata. Un producto que
            // la banda de precios descarta YA se vio: volver a pedir su página
            // en la tercera pasada es tráfico puro a cambio de descartarlo otra
            // vez. Medido contra el sitio real con precio.maximo=300000: sin
            // esta distinción la tercera pasada pide 38 páginas de ~550 KB (21
            // MB) en cada corrida; con ella pide 6.
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

    /** GET vía fetch dentro de la página ya navegada (mismo UA/cookies que el resto del scrape). */
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

    // ═══════════════════════════════════════════════════════════════════════
    // Sitemap (puro)
    // ═══════════════════════════════════════════════════════════════════════

    private static final Pattern LOC_PRODUCTO =
            Pattern.compile("<loc>\\s*https?://[^/\\s]+/productos/([^<\\s/]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOC_CATEGORIA =
            Pattern.compile("<loc>\\s*https?://[^/\\s]+/categorias/([^<\\s/]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    /** Los handles de producto del sitemap, ordenados y sin repetir. */
    static List<String> handlesDeProducto(String xml) {
        return extraer(LOC_PRODUCTO, xml);
    }

    /**
     * Los slugs de categoría del sitemap. {@code /blog/} y {@code /glosario/}
     * quedan afuera por construcción: no son catálogo y pedirlos sería tráfico
     * a cambio de nada.
     */
    static List<String> slugsDeCategoria(String xml) {
        return extraer(LOC_CATEGORIA, xml);
    }

    private static List<String> extraer(Pattern p, String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        Set<String> out = new java.util.TreeSet<>();
        Matcher m = p.matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return new ArrayList<>(out);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Payload RSC (puro, package-private para que el test no necesite Playwright)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Marca de apertura de un chunk del payload de Next.js. Cada chunk es un
     * STRING JS con JSON escapado adentro, y vienen partidos: un objeto de
     * producto puede empezar en un chunk y terminar en el siguiente, así que
     * hay que concatenar TODO antes de intentar leer nada.
     *
     * <p><b>Esto NO se puede hacer con un regex</b>, y no es preferencia de
     * estilo. El patrón natural para un string escapado es una alternancia bajo
     * cuantificador, y {@code java.util.regex} la implementa con RECURSIÓN:
     * sobre un chunk de 7,5 KB tira {@code StackOverflowError} — medido, es
     * cómo se descubrió — y los chunks reales de {@code inpro.ar} son de
     * cientos de KB. El escaneo lineal de abajo es O(n) y no toca la pila.</p>
     */
    private static final String CHUNK_INICIO = "self.__next_f.push([1,";

    /**
     * El resultado de leer un payload: los productos que pasaron los filtros, y
     * TODOS los handles que el payload mostró — hayan pasado o no.
     *
     * <p>La distinción existe porque la tercera pasada de {@link #scrapeAll()}
     * necesita saber qué handle no vio NUNCA, no cuál descartó por precio.</p>
     */
    record Lote(List<Product> productos, Set<String> handlesVistos) {}

    /** Igual que {@link #parsePayload}, con la config de la instancia. */
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

    /** Concatena y desescapa todos los chunks de {@code self.__next_f}. */
    private static String desescaparChunks(String html) {
        if (html == null || html.isBlank()) return "";
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
                // Chunk ilegible: se saltea. Los demás siguen valiendo.
                log.debug("chunk __next_f ilegible: {}", e.getMessage());
            }
            from = cierra + 1;
        }
        return sb.toString();
    }

    /**
     * Índice de la comilla que CIERRA el string que abre en {@code abre},
     * respetando el escape. Lineal y sin recursión — ver {@link #CHUNK_INICIO}.
     *
     * @return el índice de cierre, o {@code -1} si el string nunca cierra.
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
     * Todo objeto JSON del payload que tenga {@code "variants"} como clave
     * propia — es decir, todo producto Tiendanube.
     *
     * <p><b>Por qué no se ancla en {@code &#123;"id":}</b>, que sería lo obvio:
     * porque el orden de las claves NO es estable entre superficies. En las
     * páginas de categoría el objeto abre con {@code "id"}, pero en las de
     * producto abre con {@code "name"} y el {@code "id"} aparece recién
     * después de {@code "variants"} (confirmado contra
     * {@code /productos/pod-meet}). Un ancla posicional funciona en una
     * superficie y devuelve CERO en la otra, sin ningún otro síntoma — que es
     * exactamente lo que hacía antes de medirlo contra el sitio en vivo.</p>
     *
     * <p>El escaneo es lineal y con pila de llaves abiertas: cuando aparece la
     * clave {@code "variants"}, el objeto que la contiene es el del tope de la
     * pila. Saltea los strings enteros respetando el escape, porque el payload
     * tiene llaves DENTRO de strings (descripciones, HTML de SEO) y contarlas
     * parte el objeto por la mitad.</p>
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

    // ═══════════════════════════════════════════════════════════════════════
    // Mapeo
    // ═══════════════════════════════════════════════════════════════════════

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
        // compare_at_price == price en TODO el catálogo sin descuento. Copiarlo
        // igual haría que el frontend dibuje un tachado de 0% en cada producto,
        // que es peor que no mostrar nada (D1: null es "no había", no un sentinel).
        if (compare != null && compare > precio) precioOriginal = compare;

        return Optional.of(new Product(
                sitio, nombre, precio, precioOriginal,
                base + "/productos/" + handle,
                primeraImagen(n.path("images")),
                categoriaCruda(n.path("categories")),
                "",                       // genero: lo resuelve GenderResolver
                List.of(),                // talles: un mueble no tiene
                Product.MlScore.EMPTY,
                "",                       // marca: la resuelve BrandExtractor (ver abajo)
                "oficina",
                false));
    }

    /**
     * La variante más barata CON stock; si ninguna tiene, la más barata a secas.
     *
     * <p>Un producto agotado <b>igual entra al catálogo</b>, y eso es deliberado
     * y distinto de {@code TechStorePage} para CompraGamer, que descarta lo que
     * no tiene stock. La diferencia es para qué se lee cada catálogo: el de
     * CompraGamer son 1389 items de los que interesan los comprables; acá el
     * objetivo declarado es seguir el precio de sillas y escritorios, y si una
     * silla desaparece del catálogo al agotarse, el soft-delete le abre un
     * hueco al historial justo del producto que se está siguiendo.</p>
     */
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

    /**
     * Tiendanube localiza los campos de texto: {@code {"es": "...", "value": "..."}}.
     * Algunos vienen como string pelado, así que se aceptan las dos formas.
     */
    private static String textoLocalizado(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        if (n.isTextual()) return n.asText().trim();
        String es = n.path("es").asText("").trim();
        return es.isEmpty() ? n.path("value").asText("").trim() : es;
    }

    /** La imagen de {@code position} más baja. Ya viene absoluta del CDN de Tiendanube. */
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

    /**
     * La categoría MÁS específica que declara la tienda — la última del array,
     * que es la hoja del breadcrumb.
     *
     * <p>Es sólo una pista: {@code CategoryClassifier} prioriza el nombre del
     * producto y sólo acepta esta si tiene alias conocido hacia el canon
     * ({@code CODE-6}). Pasarla igual no cuesta nada y ayuda en los productos
     * cuyo nombre no dice qué son.</p>
     */
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
