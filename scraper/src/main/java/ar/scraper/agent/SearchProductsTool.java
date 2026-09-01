package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.normalize.CategoryGroups;
import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@code search_products(query?, categoria?, genero?, excluir?, precioMin?, precioMax?, limit=10)}
 * — queries the REAL current catalog snapshot ({@link ScraperService#getLastResult()}),
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

    private final ScraperService scraperService;

    public SearchProductsTool(ScraperService scraperService) {
        this.scraperService = scraperService;
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

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("default", DEFAULT_LIMIT);

        return new ToolSpec(NAME,
                "Busca productos en el catálogo real actual. Combiná los criterios que haga falta "
                        + "(se aplican todos a la vez): 'query' es texto libre sobre nombre y marca; "
                        + "'categoria' filtra por la categoría CLASIFICADA del producto y es la forma "
                        + "correcta de pedir un tipo de prenda — el nombre puede no contener esa palabra; "
                        + "'genero' filtra por género; 'excluir' descarta los productos cuyo nombre "
                        + "contenga alguno de esos términos; 'precioMin'/'precioMax' acotan el precio en "
                        + "pesos. Todo sin distinguir mayúsculas ni acentos. Hace falta al menos un "
                        + "criterio además de 'excluir'. Devuelve hasta 'limit' coincidencias reales "
                        + "(url, nombre, categoria, marca, genero, precio) — nunca datos inventados.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String query     = text(args, "query");
        String categoria = text(args, "categoria");
        String genero    = text(args, "genero");
        List<String> excluir = strings(args, "excluir");
        Double precioMin = number(args, "precioMin");
        Double precioMax = number(args, "precioMax");

        // `excluir` on its own is not a criterion: "todo menos X" is the whole catalog
        // arbitrarily truncated, which reads to the model as a real answer.
        boolean hayCriterio = !query.isBlank() || !categoria.isBlank() || !genero.isBlank()
                || precioMin != null || precioMax != null;
        if (!hayCriterio) {
            return ToolResult.error("",
                    "Hace falta al menos un criterio de búsqueda: 'query', 'categoria', 'genero', "
                            + "'precioMin' o 'precioMax'. 'excluir' por sí solo no alcanza.");
        }

        if (precioMin != null && precioMax != null && precioMin > precioMax) {
            return ToolResult.error("",
                    "Rango de precios vacío: 'precioMin' (" + precioMin + ") es mayor que 'precioMax' ("
                            + precioMax + ").");
        }

        int limit = args.path("limit").asInt(DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        AggregatedResult result = scraperService.getLastResult();
        if (result == null || result.productos() == null) {
            return ToolResult.error("",
                    "No hay datos de catálogo disponibles todavía — ejecutá un scraping primero.");
        }

        Predicate<Product> filtro = filtro(query, categoria, genero, excluir, precioMin, precioMax);
        List<Product> matches = result.productos().stream().filter(filtro).limit(limit).toList();

        ArrayNode arr = MAPPER.createArrayNode();
        for (Product p : matches) {
            ObjectNode n = arr.addObject();
            n.put("url", p.url());
            n.put("nombre", p.nombre());
            n.put("categoria", p.categoria());
            n.put("marca", p.marca());
            // genero viaja en el resultado desde que se puede filtrar por él: sin esto el
            // modelo no puede reportar sobre qué filtró, ni verificar lo que devolvió.
            n.put("genero", p.genero());
            n.put("precio", p.precio());
        }
        return ToolResult.ok("", arr.toString());
    }

    /** Todos los criterios presentes se aplican en conjunción; los ausentes no filtran. */
    private static Predicate<Product> filtro(String query, String categoria, String genero,
                                             List<String> excluir, Double precioMin, Double precioMax) {
        String needle = normalize(query);
        List<String> vetos = excluir.stream().map(SearchProductsTool::normalize)
                .filter(s -> !s.isBlank()).toList();

        return p -> {
            if (!needle.isBlank()
                    && !normalize(p.nombre()).contains(needle)
                    && !normalize(p.marca()).contains(needle)) return false;
            // Igualdad exacta, no substring: la categoría es un valor de un vocabulario
            // cerrado, y "Zapatilla" como substring se llevaría "Zapatilla Running".
            if (!categoria.isBlank() && !categoria.equalsIgnoreCase(nullToEmpty(p.categoria()))) return false;
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

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String normalize(String s) {
        return AccentStripper.strip((s == null ? "" : s).toLowerCase());
    }
}
