package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code search_products(query, limit=10)} — queries the REAL current
 * catalog snapshot ({@link ScraperService#getLastResult()}), never
 * fabricated data (llm-catalog-nlp, task 3.1/3.2). Matching is
 * case/accent-insensitive substring over {@code nombre}/{@code marca}
 * (mirrors {@code AccentStripper}'s existing normalization convention).
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
        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("default", DEFAULT_LIMIT);
        schema.putArray("required").add("query");
        return new ToolSpec(NAME,
                "Busca productos en el catálogo real actual por nombre o marca (substring, sin distinguir "
                        + "mayúsculas/minúsculas ni acentos). Devuelve hasta 'limit' coincidencias reales "
                        + "(url, nombre, categoria, marca, precio) — nunca datos inventados.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String query = args.path("query").asText("");
        if (query.isBlank()) {
            return ToolResult.error("", "El parámetro 'query' es requerido y no puede estar vacío.");
        }
        int limit = args.path("limit").asInt(DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        AggregatedResult result = scraperService.getLastResult();
        if (result == null || result.productos() == null) {
            return ToolResult.error("",
                    "No hay datos de catálogo disponibles todavía — ejecutá un scraping primero.");
        }

        String needle = normalize(query);
        List<Product> matches = result.productos().stream()
                .filter(p -> normalize(p.nombre()).contains(needle) || normalize(p.marca()).contains(needle))
                .limit(limit)
                .toList();

        ArrayNode arr = MAPPER.createArrayNode();
        for (Product p : matches) {
            ObjectNode n = arr.addObject();
            n.put("url", p.url());
            n.put("nombre", p.nombre());
            n.put("categoria", p.categoria());
            n.put("marca", p.marca());
            n.put("precio", p.precio());
        }
        return ToolResult.ok("", arr.toString());
    }

    private static String normalize(String s) {
        return AccentStripper.strip((s == null ? "" : s).toLowerCase());
    }
}
