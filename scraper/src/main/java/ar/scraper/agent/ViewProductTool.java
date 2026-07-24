package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * {@code view_product(url)} — returns a product's CURRENT
 * categoria/subCategoria/genero/marca as stored in the last aggregated
 * catalog snapshot (llm-catalog-nlp, task 3.3/3.4). Unknown url → {@code
 * is_error} (Safeguard A), never a fabricated/empty result.
 */
@Component
public class ViewProductTool implements CatalogTool {

    public static final String NAME = "view_product";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScraperService scraperService;

    public ViewProductTool(ScraperService scraperService) {
        this.scraperService = scraperService;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("url").put("type", "string");
        schema.putArray("required").add("url");
        return new ToolSpec(NAME,
                "Devuelve la clasificación ACTUAL (categoria, subCategoria, genero, marca, precio) de un "
                        + "producto real del catálogo, dado su url.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String url = args.path("url").asText("");
        if (url.isBlank()) {
            return ToolResult.error("", "El parámetro 'url' es requerido.");
        }
        Product p = find(scraperService.getLastResult(), url);
        if (p == null) {
            return ToolResult.error("", "No existe ningún producto con esa url en el catálogo actual.");
        }
        ObjectNode n = MAPPER.createObjectNode();
        n.put("url", p.url());
        n.put("nombre", p.nombre());
        n.put("categoria", p.categoria());
        n.put("subCategoria", p.subCategoria());
        n.put("genero", p.genero());
        n.put("marca", p.marca());
        n.put("precio", p.precio());
        return ToolResult.ok("", n.toString());
    }

    /**
     * Shared lookup — also used by {@link ProposeReclassifyTool} and by
     * {@code ApiController}'s {@code POST /api/agent/apply} server-side
     * re-validation (the client is never trusted to have validated).
     */
    public static Product find(AggregatedResult result, String url) {
        if (result == null || result.productos() == null) return null;
        return result.productos().stream()
                .filter(p -> url.equals(p.url()))
                .findFirst()
                .orElse(null);
    }
}
