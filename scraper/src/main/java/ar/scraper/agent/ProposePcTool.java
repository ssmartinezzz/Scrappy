package ar.scraper.agent;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.pcs.PcBuild;
import ar.scraper.pcs.PcBuildJson;
import ar.scraper.pcs.PcBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code propose_pc(presupuesto?, conGpu?, excluir?)} — runs {@link
 * PcBuilder#armar} over the live snapshot and returns the same JSON shape
 * {@code GET /api/pcs/builder} serves (pc-builder-agent-tool, T2).
 * Read-only: it never persists anything — saving a build stays in the
 * {@code /pcs} page ({@code POST /api/pcs/save}).
 *
 * <p>{@link PcBuilder} is not a Spring bean ({@code ApiController} builds
 * its own), and {@code agent/} may not depend on {@code web/} (ArchUnit
 * {@code agentNoDependeDeWeb}) — so this tool builds its own {@link
 * PcBuilder} from the injected {@link RecommendationService}.
 */
@Component
public class ProposePcTool implements CatalogTool {

    public static final String NAME = "propose_pc";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CatalogSnapshotPort catalogo;
    private final PcBuilder pcBuilder;

    public ProposePcTool(CatalogSnapshotPort catalogo, RecommendationService recommendationService) {
        this.catalogo = catalogo;
        this.pcBuilder = new PcBuilder(recommendationService);
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("presupuesto").put("type", "number");
        props.putObject("conGpu").put("type", "boolean");
        ObjectNode excluir = props.putObject("excluir");
        excluir.put("type", "array");
        excluir.putObject("items").put("type", "string");

        return new ToolSpec(NAME,
                "Arma una PC con el catálogo actual: un pick por slot (motherboard, CPU, RAM, gabinete, "
                        + "fuente, almacenamiento y, si conGpu=true, placa de video), respetando compatibilidad "
                        + "(socket, DDR, form factor, watts) y un presupuesto opcional en pesos (0 o ausente = "
                        + "sin tope). 'excluir' es una lista de urls de picks que el usuario rechazó, para que "
                        + "no se repitan en el próximo armado. NUNCA guarda nada — si el usuario quiere "
                        + "conservar el armado, lo guarda desde la página /pcs.",
                schema);
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode presupuestoNode = args.path("presupuesto");
        double presupuesto = 0;
        if (!presupuestoNode.isMissingNode() && !presupuestoNode.isNull()) {
            if (!presupuestoNode.isNumber()) {
                return ToolResult.error("", "El parámetro 'presupuesto' tiene que ser un número.");
            }
            presupuesto = presupuestoNode.asDouble();
            if (presupuesto < 0) {
                return ToolResult.error("", "El parámetro 'presupuesto' no puede ser negativo.");
            }
        }
        boolean conGpu = args.path("conGpu").asBoolean(false);

        Set<String> excluir = new LinkedHashSet<>();
        if (args.path("excluir").isArray()) {
            for (JsonNode n : args.path("excluir")) {
                String url = n.asText("");
                if (!url.isBlank()) excluir.add(url);
            }
        }

        AggregatedResult result = catalogo.getLastResult();
        if (result == null || result.productos() == null) {
            return ToolResult.error("", "No hay catálogo cargado todavía. Corré un scraping primero.");
        }

        PcBuild build = pcBuilder.armar(result.productos(), presupuesto, conGpu, excluir);
        return ToolResult.ok("", PcBuildJson.toJson(build).toString());
    }
}
