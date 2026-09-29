package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.classification.CategoryGroups;
import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import ar.scraper.aggregator.CatalogSnapshotPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/**
 * {@code search_products(query?, categoria?, genero?, excluir?, precioMin?, precioMax?, enOferta?, limit=10)}
 * — queries the REAL current catalog snapshot ({@link CatalogSnapshotPort#getLastResult()}),
 * never fabricated data (llm-catalog-nlp, task 3.1/3.2).
 *
 * <h2>Why the structured filters exist</h2>
 *
 * <p>The tool used to take a free-text {@code query} only, matched as a substring over
 * {@code nombre}/{@code marca}. That left an ordinary request — "musculosas que no sean
 * de fútbol y por menos de $50.000" — unanswerable for two independent reasons:</p>
 *
 * <ul>
 *   <li>a CATEGORY is not a word in the name. A product classified {@code Musculosa} and
 *       named "Remera sin mangas Dry Fit" was invisible to {@code query=musculosa} — and
 *       a product whose name disagrees with its category is precisely the one worth
 *       reviewing, so the blind spot lined up exactly with the tool's purpose;</li>
 *   <li>substring matching cannot express "not", or a price ceiling.</li>
 * </ul>
 *
 * <p>Handing the model an unfiltered result and letting it narrow in prose is worse than
 * it looks: the grounding gate in {@link CatalogAgentService} sees a real tool call with
 * real rows and passes the turn, so a prose-filtered answer is indistinguishable from a
 * fetched one. Every criterion the user states has to be a criterion the catalog applies.</p>
 */
@Component
public class SearchProductsTool implements CatalogTool {

    public static final String NAME = "search_products";
    private static final int DEFAULT_LIMIT = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** "1 tb" → "1tb": un número separado de su unidad es el mismo término que el pegado. */
    private static final Pattern NUMERO_UNIDAD = Pattern.compile("(\\d) +(?=[a-z])");

    /** Tope de términos de contenido por consulta (la máscara de coincidencia es un int). */
    private static final int MAX_TERMINOS = 16;
    /** Con al menos esta fracción de los términos, una fila entra en el modo relajado. */
    private static final double COBERTURA_MINIMA = 0.5;
    /** Con enOferta=true estas palabras ya están expresadas por el parámetro: no son términos de búsqueda. */
    private static final Set<String> PALABRAS_OFERTA = Set.of(
            "descuento", "oferta", "rebaja", "rebajado", "promo", "promocion");

    /** Las claves que declara {@link #spec()}; cualquier otra es un error. */
    private static final List<String> ARGUMENTOS = List.of(
            "query", "categoria", "genero", "excluir", "precioMin", "precioMax", "enOferta", "limit");

    private final CatalogSnapshotPort catalogo;
    private final RelevanceRanker ranker = new RelevanceRanker();

    public SearchProductsTool(CatalogSnapshotPort catalogo) {
        this.catalogo = catalogo;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("query").put("type", "string");

        // Closed enum, same idiom as propose_reclassify (CODE-6): the canon is the one
        // owner of the vocabulary, so the model cannot ask for a category that cannot exist.
        ObjectNode categoria = props.putObject("categoria");
        categoria.put("type", "string");
        ArrayNode cats = categoria.putArray("enum");
        CategoryGroups.canonicalCategories().stream().sorted().forEach(cats::add);

        ObjectNode genero = props.putObject("genero");
        genero.put("type", "string");
        ArrayNode gens = genero.putArray("enum");
        List.of("hombre", "mujer", "unisex", "infantil").forEach(gens::add);

        ObjectNode excluir = props.putObject("excluir");
        excluir.put("type", "array");
        excluir.putObject("items").put("type", "string");

        props.putObject("precioMin").put("type", "number");
        props.putObject("precioMax").put("type", "number");
        props.putObject("enOferta").put("type", "boolean");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("default", DEFAULT_LIMIT);

        return new ToolSpec(NAME,
                "Busca productos en el catálogo real actual. Combiná los criterios que haga falta "
                        + "(se aplican todos a la vez): 'query' es texto libre sobre nombre, marca y categoría; "
                        + "'categoria' filtra por la categoría CLASIFICADA del producto (incluye su familia: "
                        + "'Zapatilla' abarca 'Zapatilla Running') y es la forma correcta de pedir un tipo de "
                        + "prenda — el nombre puede no contener esa palabra; "
                        + "'genero' filtra por género; 'excluir' descarta los productos cuyo nombre "
                        + "contenga alguno de esos términos; 'precioMin'/'precioMax' acotan el precio en "
                        + "pesos; 'enOferta'=true deja solo los productos con descuento (precio original "
                        + "mayor al actual) y los ordena de mayor a menor descuento. 'query' se separa en "
                        + "palabras (se ignoran las vacías como 'de', 'tenés', 'algún'), admite plurales y "
                        + "errores de tipeo leves, y busca TODAS las palabras en cualquier orden; los "
                        + "resultados vienen ordenados por 'relevancia'. Si ningún producto cumple todas las "
                        + "palabras, devuelve los que cumplen al menos la mitad, cada uno con "
                        + "coincidencia=\"parcial\" y terminosFaltantes: NO son la respuesta exacta y hay que "
                        + "decírselo al usuario. Todo sin distinguir mayúsculas ni acentos. Hace falta al menos un "
                        + "criterio además de 'excluir'. Devuelve hasta 'limit' coincidencias reales "
                        + "(url, nombre, sitio, categoria, subCategoria, marca, genero, precio, relevancia y, cuando "
                        + "hay descuento, precioOrig y descuentoPct) — nunca datos inventados.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        // Un argumento inventado (marca, ddr…) descartado en silencio hace que el modelo presente
        // como respuesta una búsqueda que no aplicó su criterio: se rechaza antes de filtrar nada.
        List<String> desconocidas = new ArrayList<>();
        args.fieldNames().forEachRemaining(k -> { if (!ARGUMENTOS.contains(k)) desconocidas.add(k); });
        if (!desconocidas.isEmpty()) {
            return ToolResult.error("",
                    "Argumento(s) desconocido(s): " + String.join(", ", desconocidas) + ". Los válidos son: "
                            + String.join(", ", ARGUMENTOS) + ". La marca, el modelo y las especificaciones "
                            + "(DDR, capacidad, etc.) van dentro de 'query'.");
        }
        String query     = text(args, "query");
        String categoria = text(args, "categoria");
        String genero    = text(args, "genero");
        List<String> excluir = strings(args, "excluir");
        Double precioMin = number(args, "precioMin");
        Double precioMax = number(args, "precioMax");
        boolean enOferta = args.path("enOferta").asBoolean(false);

        List<QueryTokenizer.Token> tokens = new ArrayList<>(QueryTokenizer.contentTokens(query));
        // Con enOferta=true "en descuento" dentro de la query es redundante: ya lo expresa el parámetro.
        if (enOferta) tokens.removeIf(t -> PALABRAS_OFERTA.contains(t.stem()));
        if (tokens.size() > MAX_TERMINOS) tokens = tokens.subList(0, MAX_TERMINOS);

        // `excluir` (y `enOferta`) on its own is not a criterion: "todo menos X" / "todo lo
        // rebajado" is the whole catalog arbitrarily truncated, which reads to the model as a real answer.
        boolean hayCriterio = !tokens.isEmpty() || !categoria.isBlank() || !genero.isBlank()
                || precioMin != null || precioMax != null;
        if (!hayCriterio) {
            if (!query.isBlank()) {
                return ToolResult.error("",
                        "La 'query' solo tenía palabras vacías (artículos, muletillas" + (enOferta ? ", 'descuento'" : "")
                                + "): no queda nada para buscar. Pasá palabras del producto (tipo, marca, modelo, "
                                + "capacidad) o usá 'categoria', 'genero', 'precioMin' o 'precioMax'.");
            }
            return ToolResult.error("",
                    "Hace falta al menos un criterio de búsqueda: 'query', 'categoria', 'genero', "
                            + "'precioMin' o 'precioMax'. 'excluir' y 'enOferta' por sí solos no alcanzan.");
        }

        if (precioMin != null && precioMax != null && precioMin > precioMax) {
            return ToolResult.error("",
                    "Rango de precios vacío: 'precioMin' (" + precioMin + ") es mayor que 'precioMax' ("
                            + precioMax + ").");
        }

        int limit = args.path("limit").asInt(DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        AggregatedResult result = catalogo.getLastResult();
        if (result == null || result.productos() == null) {
            return ToolResult.error("",
                    "No hay datos de catálogo disponibles todavía — ejecutá un scraping primero.");
        }

        // Filtros duros primero (categoría o su familia, género, precio, excluir, oferta); recién después el texto.
        List<Product> productos = result.productos();
        Predicate<Product> filtro = filtroDuro(categoria, genero, excluir, precioMin, precioMax, enOferta);
        List<Integer> candidatos = new ArrayList<>();
        for (int i = 0; i < productos.size(); i++) {
            if (filtro.test(productos.get(i))) candidatos.add(i);
        }

        double[] score = null;
        int[] mask = null;
        boolean parcial = false;
        int total = tokens.size();
        if (total > 0) {
            var scores = ranker.score(result, tokens.stream().map(QueryTokenizer.Token::stem).toList());
            score = scores.score();
            mask = scores.mask();
            int completo = (1 << total) - 1;
            List<Integer> estrictos = new ArrayList<>();
            for (int i : candidatos) if (mask[i] == completo) estrictos.add(i);
            if (!estrictos.isEmpty()) {
                candidatos = estrictos;
            } else {
                parcial = true;
                List<Integer> relajados = new ArrayList<>();
                for (int i : candidatos) {
                    int cubiertos = Integer.bitCount(mask[i]);
                    if (cubiertos > 0 && (double) cubiertos / total >= COBERTURA_MINIMA) relajados.add(i);
                }
                candidatos = relajados;
            }
        }

        // El orden va ANTES del límite: si no, "las mejores ofertas" serían las primeras N del catálogo.
        // List.sort es estable: los empates conservan el orden del catálogo.
        final double[] sc = score;
        Comparator<Integer> orden = null;
        if (sc != null) orden = Comparator.comparingDouble((Integer i) -> sc[i]).reversed();
        if (enOferta) {
            Comparator<Integer> porDescuento =
                    // Por el % redondeado que ve el usuario: la fracción cruda ordena por ruido de
                    // redondeo del sitio y le gana a la relevancia en los empates reales.
                    Comparator.comparingLong((Integer i) -> Math.round(descuento(productos.get(i)) * 100))
                            .reversed();
            orden = orden == null ? porDescuento : porDescuento.thenComparing(orden);
        }
        if (orden != null) candidatos.sort(orden);

        ArrayNode arr = MAPPER.createArrayNode();
        for (int i : candidatos.subList(0, Math.min(limit, candidatos.size()))) {
            Product p = productos.get(i);
            ObjectNode n = arr.addObject();
            n.put("url", p.url());
            n.put("nombre", p.nombre());
            n.put("sitio", p.sitio());
            n.put("categoria", p.categoria());
            // Con categoria+subCategoria+marca+genero en el resultado, reclasificar no exige view_product.
            n.put("subCategoria", p.subCategoria());
            n.put("marca", p.marca());
            // genero viaja en el resultado desde que se puede filtrar por él: sin esto el
            // modelo no puede reportar sobre qué filtró, ni verificar lo que devolvió.
            n.put("genero", p.genero());
            n.put("precio", p.precio());
            if (enDescuento(p)) {
                n.put("precioOrig", p.precioOriginal());
                n.put("descuentoPct", (int) Math.round(descuento(p) * 100));
            }
            if (score != null) {
                n.put("relevancia", Math.round(score[i] * 100) / 100.0);
                // Las filas estrictas no llevan bandera: sólo las parciales se marcan.
                if (parcial) {
                    n.put("coincidencia", "parcial");
                    ArrayNode faltan = n.putArray("terminosFaltantes");
                    for (int t = 0; t < total; t++) {
                        if ((mask[i] & (1 << t)) == 0) faltan.add(tokens.get(t).word());
                    }
                }
            }
        }
        return ToolResult.ok("", arr.toString());
    }

    /** Todos los criterios presentes se aplican en conjunción; los ausentes no filtran. El texto va aparte (ranking). */
    private static Predicate<Product> filtroDuro(String categoria, String genero,
                                                 List<String> excluir, Double precioMin, Double precioMax,
                                                 boolean enOferta) {
        List<String> vetos = excluir.stream().map(SearchProductsTool::normalize)
                .filter(s -> !s.isBlank()).toList();

        return p -> {
            if (enOferta && !enDescuento(p)) return false;
            // Familia, no substring: "Zapatilla" abarca "Zapatilla Running/Urbana…" (el modelo pide el
            // tipo genérico y la taxonomía lo subdivide), pero el prefijo termina en palabra completa —
            // "Remera" no se lleva "Buzo Remera" ni "Remerón". Antes era igualdad exacta y daba falsos "no hay".
            if (!categoria.isBlank() && !enFamilia(categoria, nullToEmpty(p.categoria()))) return false;
            if (!genero.isBlank() && !genero.equalsIgnoreCase(nullToEmpty(p.genero()))) return false;
            if (precioMin != null && p.precio() < precioMin) return false;
            if (precioMax != null && p.precio() > precioMax) return false;
            if (!vetos.isEmpty()) {
                String nombre = normalize(p.nombre());
                for (String veto : vetos) if (nombre.contains(veto)) return false;
            }
            return true;
        };
    }

    private static String text(JsonNode args, String field) {
        return args.path(field).asText("").trim();
    }

    private static Double number(JsonNode args, String field) {
        JsonNode n = args.get(field);
        if (n == null || n.isNull() || !n.isNumber()) return null;
        return n.asDouble();
    }

    private static List<String> strings(JsonNode args, String field) {
        JsonNode n = args.get(field);
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            String s = item.asText("").trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** Igual (sin mayúsculas) o empieza con {@code familia + " "}. */
    private static boolean enFamilia(String familia, String categoria) {
        return categoria.equalsIgnoreCase(familia)
                || (categoria.length() > familia.length()
                    && categoria.regionMatches(true, 0, familia + " ", 0, familia.length() + 1));
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static boolean enDescuento(Product p) {
        return p.precioOriginal() != null && p.precioOriginal() > p.precio();
    }

    /** Fracción descontada (0..1); 0 si no hay descuento real. */
    private static double descuento(Product p) {
        return enDescuento(p) ? 1 - p.precio() / p.precioOriginal() : 0;
    }

    private static String normalize(String s) {
        String base = AccentStripper.strip((s == null ? "" : s).toLowerCase()).trim();
        return NUMERO_UNIDAD.matcher(base).replaceAll("$1");
    }
}
