package ar.scraper.agent;

import ar.scraper.aggregator.normalize.CategoryGroups;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code propose_reclassify(url, categoria, subCategoria?, marca?, genero?)}
 * — llm-catalog-nlp design D4, Safeguards A+B. VALIDATES (url exists in the
 * current catalog; {@code categoria} ∈ {@link CategoryGroups#canonicalCategories()})
 * and RETURNS a {@link ReclassifyProposal} diff. This class deliberately
 * holds NO {@link ar.scraper.db.DatabaseService} reference at all — it is
 * architecturally impossible for this tool to write to {@code productos};
 * the only write path is the separate, out-of-loop, human-confirmed
 * {@code POST /api/agent/apply} endpoint.
 */
@Component
public class ProposeReclassifyTool implements CatalogTool {

    public static final String NAME = "propose_reclassify";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScraperService scraperService;

    public ProposeReclassifyTool(ScraperService scraperService) {
        this.scraperService = scraperService;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("url").put("type", "string");

        ObjectNode categoria = props.putObject("categoria");
        categoria.put("type", "string");
        ArrayNode enumArr = categoria.putArray("enum");
        CategoryGroups.canonicalCategories().stream().sorted().forEach(enumArr::add);

        props.putObject("subCategoria").put("type", "string");
        props.putObject("marca").put("type", "string");
        props.putObject("genero").put("type", "string");

        ArrayNode required = schema.putArray("required");
        required.add("url");
        required.add("categoria");

        return new ToolSpec(NAME,
                "Propone una reclasificación (categoria obligatoria; subCategoria/marca/genero opcionales) "
                        + "para un producto real, identificado por su url. NUNCA escribe en la base de datos — "
                        + "solo devuelve un diff (valor actual → valor propuesto) que el usuario debe confirmar "
                        + "explícitamente en la interfaz antes de que se aplique ningún cambio.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        String url = args.path("url").asText("");
        if (url.isBlank()) {
            return ToolResult.error("", "El parámetro 'url' es requerido.");
        }
        String categoriaPropuesta = args.path("categoria").asText("");
        if (categoriaPropuesta.isBlank()) {
            return ToolResult.error("", "El parámetro 'categoria' es requerido.");
        }

        Product current = ViewProductTool.find(scraperService.getLastResult(), url);
        if (current == null) {
            return ToolResult.error("", "No existe ningún producto con esa url en el catálogo actual.");
        }

        List<String> validCategorias = CategoryGroups.canonicalCategories().stream().sorted().toList();
        if (!validCategorias.contains(categoriaPropuesta)) {
            return ToolResult.error("",
                    "Categoría inválida: '" + categoriaPropuesta + "'. Valores válidos: "
                            + String.join(", ", validCategorias));
        }

        String subCategoria = optionalText(args, "subCategoria", current.subCategoria());
        String marca        = optionalText(args, "marca", current.marca());
        String genero        = optionalText(args, "genero", current.genero());

        ReclassifyProposal proposal = new ReclassifyProposal(
                url, current.nombre(), current.categoria(), categoriaPropuesta,
                subCategoria, marca, genero);
        try {
            return ToolResult.ok("", MAPPER.writeValueAsString(proposal));
        } catch (Exception e) {
            return ToolResult.error("", "Error interno generando la propuesta de reclasificación.");
        }
    }

    private static String optionalText(JsonNode args, String field, String fallback) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText("").isBlank()) return fallback;
        return node.asText();
    }
}
