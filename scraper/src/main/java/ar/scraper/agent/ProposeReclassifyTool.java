package ar.scraper.agent;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.model.Product;
import ar.scraper.aggregator.CatalogSnapshotPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ProposeReclassifyTool implements CatalogTool {

    public static final String NAME = "propose_reclassify";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Public because this tool is NOT the write path — it only returns a proposal diff.
     * {@code AgentController.agentApply} is what actually writes, is reachable without ever calling
     * this tool, and validates against this same set.
     */
    public static final Set<String> VALID_GENEROS =
            Set.of("hombre", "mujer", "unisex", "infantil", "");

    private final CatalogSnapshotPort catalogo;

    public ProposeReclassifyTool(CatalogSnapshotPort catalogo) {
        this.catalogo = catalogo;
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

        Product current = ViewProductTool.find(catalogo.getLastResult(), url);
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

        if (!VALID_GENEROS.contains(genero)) {
            return ToolResult.error("",
                    "Género inválido: '" + genero + "'. Valores válidos: "
                            + String.join(", ", VALID_GENEROS.stream().sorted().toList()) + ".");
        }

        // A diff that changes nothing is noise for the user (a card whose "confirm" is a no-op) and
        // a loop for the model: it is an error so the model stops instead of restating the same
        // thing.
        if (same(categoriaPropuesta, current.categoria()) && same(subCategoria, current.subCategoria())
                && same(marca, current.marca()) && same(genero, current.genero())) {
            return ToolResult.error("",
                    "El producto ya tiene esa clasificación: no hay nada que cambiar, no propongas este cambio.");
        }

        ReclassifyProposal proposal = new ReclassifyProposal(
                url, current.nombre(), current.categoria(), categoriaPropuesta,
                subCategoria, marca, genero);
        try {
            return ToolResult.ok("", MAPPER.writeValueAsString(proposal));
        } catch (Exception e) {
            return ToolResult.error("", "Error interno generando la propuesta de reclasificación.");
        }
    }

    /** Null-safe; null and "" are the same "no value" (see the genero abstention sentinel). */
    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static String optionalText(JsonNode args, String field, String fallback) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText("").isBlank()) return fallback;
        return node.asText();
    }
}
