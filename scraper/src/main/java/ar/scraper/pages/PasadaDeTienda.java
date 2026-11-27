package ar.scraper.pages;

import ar.scraper.pages.VtexPage.CategoriaVtex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the storefront listings ({@code /l/<path>/?vendedor=...&page=N}) of a VTEX site whose API stock is
 * unreliable. The listing is the stock signal (listed = in stock) and its {@code item.title} is the full
 * product name. Its {@code N-cuotas-sin-interes} collection is the installments the common cards get. Any
 * failure throws: the caller then treats every row as "unknown".
 */
final class PasadaDeTienda {

    private static final Logger LOG = LoggerFactory.getLogger(PasadaDeTienda.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NEXT_DATA =
            Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern CODIGO_EN_URL = Pattern.compile("-(\\d+)/p$");
    private static final Pattern COLECCION_CUOTAS = Pattern.compile("^(\\d+)-cuotas-sin-interes$");
    /** Only the collections checked against the product page (2026-11-27, 3 pages each); any other N is ignored. */
    static final Set<Integer> CUOTAS_VERIFICADAS = Set.of(3, 6, 9, 12);

    /** The storefront window: a listing never shows more than this many items. */
    static final int VENTANA = 1_000;
    static final double COBERTURA_MINIMA = 0.97;
    private static final int PAGINAS_MAXIMAS = 600;
    private static final int REINTENTOS = 2;
    private static final long PAUSA_MS = 150;
    private static final String VENDEDOR = "fravega";

    /** One listing actually read: the category ids it covers and what it announced against what came back. */
    record Listado(Set<Integer> ids, int anunciados, int capturados) {
        boolean incompleto() { return capturados < anunciados * COBERTURA_MINIMA; }
    }

    /** What the pass saw: code -> storefront title, code -> verified collection installments, and the listings. */
    record Resultado(Map<String, String> titulos, Map<String, Integer> cuotas, List<Listado> listados, int paginas) {

        /** Seen on the storefront: in stock. Absent: only a complete covering listing plus a low API quantity says out. */
        Boolean stock(String codigo, Integer hoja, Integer cantidadApi) {
            if (codigo != null && titulos.containsKey(codigo)) return Boolean.TRUE;
            Listado cubre = cubrimiento(hoja);
            if (cubre == null || cubre.incompleto()) return null;
            return cantidadApi != null && cantidadApi <= 10 ? Boolean.FALSE : null;
        }

        private Listado cubrimiento(Integer hoja) {
            if (hoja == null) return null;
            for (Listado l : listados) if (l.ids().contains(hoja)) return l;
            return null;
        }

        int anunciados() { return listados.stream().mapToInt(Listado::anunciados).sum(); }
        int capturados() { return listados.stream().mapToInt(Listado::capturados).sum(); }
    }

    record Pagina(int total, List<JsonNode> resultados) { }

    private final String dominio;
    private final String sitio;
    private final Function<String, String> lector;
    private final Runnable pausa;
    private final Map<String, String> titulos = new HashMap<>();
    private final Map<String, Integer> cuotas = new HashMap<>();
    private final List<Listado> listados = new ArrayList<>();
    private int paginas;

    /** {@code lector} returns the body of a URL or throws; {@code pausa} spaces the requests out. */
    PasadaDeTienda(String dominio, String sitio, Function<String, String> lector, Runnable pausa) {
        this.dominio = dominio;
        this.sitio = sitio;
        this.lector = lector;
        this.pausa = pausa;
    }

    static String codigoDe(String url) {
        Matcher m = CODIGO_EN_URL.matcher(StringUtils.defaultString(url));
        return m.find() ? m.group(1) : null;
    }

    Resultado ejecutar(List<CategoriaVtex> arbol, Set<Integer> excluidos) {
        if (arbol.isEmpty()) throw new IllegalStateException("sin árbol de categorías: no hay listados que leer");
        for (CategoriaVtex c : arbol) leer(c, excluidos);
        Resultado r = new Resultado(Map.copyOf(titulos), Map.copyOf(cuotas), List.copyOf(listados), paginas);
        LOG.info("[{}] Pasada de tienda: {} listados, {} páginas, {} anunciados, {} capturados, {} códigos, {} con cuotas",
                sitio, r.listados().size(), r.paginas(), r.anunciados(), r.capturados(), r.titulos().size(),
                r.cuotas().size());
        return r;
    }

    private void leer(CategoriaVtex c, Set<Integer> excluidos) {
        if (excluidos.contains(c.id())) return;
        String ruta = rutaDe(c);
        if (ruta == null) {
            for (CategoriaVtex h : c.hijos()) leer(h, excluidos);
            return;
        }
        Pagina primera = pagina(ruta, 1);
        if (primera.total() > VENTANA && !c.hijos().isEmpty()) {
            acumular(primera.resultados());
            for (CategoriaVtex h : c.hijos()) leer(h, excluidos);
            return;
        }
        int capturados = acumular(primera.resultados());
        int pag = 1;
        boolean hayMas = !primera.resultados().isEmpty();
        while (hayMas && capturados < primera.total()) {
            Pagina siguiente = pagina(ruta, ++pag);
            hayMas = !siguiente.resultados().isEmpty();
            capturados += acumular(siguiente.resultados());
        }
        listados.add(new Listado(idsDe(c, excluidos), primera.total(), capturados));
    }

    /** Path of the node under the site, e.g. {@code tv-y-video/tv}; null when the tree gave no URL. */
    private String rutaDe(CategoriaVtex c) {
        String url = c.url();
        if (StringUtils.isBlank(url) || !url.startsWith(dominio + "/")) return null;
        String ruta = StringUtils.strip(url.substring(dominio.length() + 1), "/");
        return ruta.isEmpty() ? null : ruta;
    }

    private static Set<Integer> idsDe(CategoriaVtex c, Set<Integer> excluidos) {
        Set<Integer> ids = new HashSet<>();
        recolectar(c, excluidos, ids);
        return ids;
    }

    private static void recolectar(CategoriaVtex c, Set<Integer> excluidos, Set<Integer> ids) {
        if (excluidos.contains(c.id())) return;
        ids.add(c.id());
        for (CategoriaVtex h : c.hijos()) recolectar(h, excluidos, ids);
    }

    private int acumular(List<JsonNode> resultados) {
        for (JsonNode n : resultados) {
            String codigo = n.path("code").asText("");
            if (!codigo.isEmpty() && VENDEDOR.equals(n.path("seller").path("id").asText(""))) {
                titulos.put(codigo, n.path("item").path("title").asText("").trim());
                Integer c = cuotasDeColeccion(n.path("collections"));
                if (c != null) cuotas.put(codigo, c);
            }
        }
        return resultados.size();
    }

    /** The largest verified {@code N-cuotas-sin-interes} the item carries; null when it carries none. */
    static Integer cuotasDeColeccion(JsonNode colecciones) {
        Integer max = null;
        for (JsonNode c : colecciones) {
            Matcher m = COLECCION_CUOTAS.matcher(c.path("slug").asText(""));
            if (!m.matches()) continue;
            int n = Integer.parseInt(m.group(1));
            if (CUOTAS_VERIFICADAS.contains(n) && (max == null || n > max)) max = n;
        }
        return max;
    }

    private Pagina pagina(String ruta, int n) {
        if (++paginas > PAGINAS_MAXIMAS) {
            throw new IllegalStateException("más de " + PAGINAS_MAXIMAS + " páginas: la pasada se corta");
        }
        String url = dominio + "/l/" + ruta + "/?vendedor=" + VENDEDOR + "&page=" + n;
        RuntimeException ultimo = null;
        for (int i = 0; i <= REINTENTOS; i++) {
            try {
                if (n > 1 || paginas > 1) pausa.run();
                return parsear(lector.apply(url));
            } catch (RuntimeException e) {
                ultimo = e;
                LOG.debug("[{}] Pasada de tienda: {} falló ({}), intento {}", sitio, url, e.getMessage(), i + 1);
            }
        }
        throw new IllegalStateException("no se pudo leer " + url + ": " + ultimo.getMessage(), ultimo);
    }

    static Pagina parsear(String html) {
        Matcher m = NEXT_DATA.matcher(StringUtils.defaultString(html));
        if (!m.find()) throw new IllegalStateException("sin __NEXT_DATA__");
        try {
            JsonNode raiz = MAPPER.readTree(m.group(1)).path("props").path("pageProps")
                    .path("__APOLLO_STATE__").path("ROOT_QUERY");
            if (!raiz.isObject()) throw new IllegalStateException("sin ROOT_QUERY");
            var campos = raiz.fields();
            while (campos.hasNext()) {
                var campo = campos.next();
                if (!campo.getKey().startsWith("items(")) continue;
                return paginaDe(campo.getValue());
            }
            return new Pagina(0, List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("__NEXT_DATA__ ilegible: " + e.getOriginalMessage(), e);
        }
    }

    private static Pagina paginaDe(JsonNode items) {
        List<JsonNode> resultados = new ArrayList<>();
        var campos = items.fields();
        while (campos.hasNext()) {
            var campo = campos.next();
            if (campo.getKey().contains("results") && campo.getValue().isArray()) {
                campo.getValue().forEach(resultados::add);
            }
        }
        return new Pagina(items.path("total").asInt(0), resultados);
    }
}
