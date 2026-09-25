package ar.scraper.agent;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.GamaWire;
import ar.scraper.pcs.PcBuild;
import ar.scraper.pcs.PcBuildJson;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.PreferenciasWire;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.Uso;
import ar.scraper.pcs.UsoWire;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code propose_pc(presupuesto?, conGpu?, excluir?, gama?)} — runs {@link
 * PcBuilder#armar} over the live snapshot and returns the same JSON shape
 * {@code GET /api/pcs/builder} serves (pc-builder-agent-tool, T2;
 * {@code gama} added in pc-builder-gama T6). Read-only: it never persists
 * anything — saving a build stays in the {@code /pcs} page ({@code
 * POST /api/pcs/save}).
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
        this.pcBuilder = new PcBuilder();
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
        ObjectNode gama = props.putObject("gama");
        gama.put("type", "string");
        ArrayNode gamaEnum = gama.putArray("enum");
        gamaEnum.add("economica").add("media").add("alta");
        ObjectNode ddr = props.putObject("ddr");
        ddr.put("type", "string");
        ddr.putArray("enum").add("ddr4").add("ddr5");
        ObjectNode marcaCpu = props.putObject("marcaCpu");
        marcaCpu.put("type", "string");
        marcaCpu.putArray("enum").add("intel").add("amd");
        ObjectNode marcaGpu = props.putObject("marcaGpu");
        marcaGpu.put("type", "string");
        marcaGpu.putArray("enum").add("nvidia").add("amd");
        ObjectNode tipoAlmacenamiento = props.putObject("tipoAlmacenamiento");
        tipoAlmacenamiento.put("type", "string");
        tipoAlmacenamiento.putArray("enum").add("nvme").add("sata").add("hdd");
        props.putObject("ramDual").put("type", "boolean");
        props.putObject("wifi").put("type", "boolean");
        props.putObject("capacidadMinimaGb").put("type", "integer");
        ObjectNode tamanioGabinete = props.putObject("tamanioGabinete");
        tamanioGabinete.put("type", "string");
        tamanioGabinete.putArray("enum").add("mini").add("mid").add("full");
        ObjectNode tipoCooler = props.putObject("tipoCooler");
        tipoCooler.put("type", "string");
        tipoCooler.putArray("enum").add("liquido").add("aire");
        props.putObject("wattsMinimos").put("type", "integer");
        ObjectNode uso = props.putObject("uso");
        uso.put("type", "string");
        uso.putArray("enum").add("gaming").add("homelab");

        return new ToolSpec(NAME,
                "Arma una PC con el catálogo actual: un pick por slot (motherboard, CPU, RAM, gabinete, "
                        + "fuente, almacenamiento y, si conGpu=true, placa de video), respetando compatibilidad "
                        + "(socket, DDR, form factor, watts) y un presupuesto opcional en pesos (0 o ausente = "
                        + "sin tope). 'excluir' es una lista de urls de picks que el usuario rechazó, para que "
                        + "no se repitan en el próximo armado. 'gama' es un filtro DURO de potencia opcional "
                        + "('economica'/'media'/'alta'): pedirla exige que cada componente relevante alcance ese "
                        + "tier, y un componente cuyo nombre no se pudo leer queda afuera, no adentro. "
                        + "'ddr'/'marcaCpu'/'marcaGpu'/'tipoAlmacenamiento' son filtros DUROS opcionales más "
                        + "(generación de RAM/motherboard, marca del chip de CPU/GPU, tecnología de disco) y "
                        + "'ramDual'/'wifi' piden un kit dual (2x) y una motherboard con wifi respectivamente — "
                        + "los seis se comportan igual que 'gama': un componente cuyo nombre no se pudo leer "
                        + "queda afuera, no adentro. 'capacidadMinimaGb' y 'wattsMinimos' son PISOS ('al "
                        + "menos N'), no valores exactos: pedir 1024 GB deja entrar un disco de 2 TB. "
                        + "'tamanioGabinete' ('mini'/'mid'/'full') es el tamaño de torre — NO el form factor de "
                        + "la placa — y el catálogo lo declara en pocos gabinetes, así que pedirlo achica mucho "
                        + "el pool. 'tipoCooler' ('liquido'/'aire') además ABRE el slot de cooler aunque la gama "
                        + "no sea alta. 'uso' ('gaming'/'homelab', default 'gaming') cambia el layout entero: "
                        + "homelab arma DOS discos ('sistema' de arranque + 'datos' a granel, capacidad primero, "
                        + "HDD preferido) y prioriza la RAM por capacidad. Combinado con tamanioGabinete='mini' "
                        + "arma UN solo pick de categoría Mini PC en vez de motherboard/cpu/ram/gabinete/fuente "
                        + "sueltos. NUNCA guarda nada — si el usuario quiere conservar el "
                        + "armado, lo guarda desde la página /pcs.",
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

        Gama gamaPedida;
        try {
            gamaPedida = GamaWire.parse(args.path("gama").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'gama' tiene que ser 'economica', 'media' o 'alta'.");
        }

        String ddr;
        try {
            ddr = PreferenciasWire.parseDdr(args.path("ddr").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'ddr' tiene que ser 'ddr4' o 'ddr5'.");
        }
        String marcaCpu;
        try {
            marcaCpu = PreferenciasWire.parseMarcaCpu(args.path("marcaCpu").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'marcaCpu' tiene que ser 'intel' o 'amd'.");
        }
        String marcaGpu;
        try {
            marcaGpu = PreferenciasWire.parseMarcaGpu(args.path("marcaGpu").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'marcaGpu' tiene que ser 'nvidia' o 'amd'.");
        }
        TipoAlmacenamiento tipoAlmacenamiento;
        try {
            tipoAlmacenamiento = PreferenciasWire.parseTipoAlmacenamiento(args.path("tipoAlmacenamiento").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'tipoAlmacenamiento' tiene que ser 'nvme', 'sata' o 'hdd'.");
        }
        Boolean ramDual = args.hasNonNull("ramDual") ? args.path("ramDual").asBoolean() : null;
        Boolean wifi = args.hasNonNull("wifi") ? args.path("wifi").asBoolean() : null;

        ar.scraper.pcs.TamanioGabinete tamanioGabinetePedido;
        try {
            tamanioGabinetePedido = PreferenciasWire.parseTamanioGabinete(
                    args.path("tamanioGabinete").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'tamanioGabinete' tiene que ser 'mini', 'mid' o 'full'.");
        }
        ar.scraper.pcs.TipoCooler tipoCoolerPedido;
        try {
            tipoCoolerPedido = PreferenciasWire.parseTipoCooler(args.path("tipoCooler").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'tipoCooler' tiene que ser 'liquido' o 'aire'.");
        }

        Uso usoPedido;
        try {
            usoPedido = UsoWire.parse(args.path("uso").asText(null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", "El parámetro 'uso' tiene que ser 'gaming' o 'homelab'.");
        }

        PreferenciasDeArmado prefs;
        try {
            prefs = new PreferenciasDeArmado(ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi,
                    enteroPositivo(args, "capacidadMinimaGb"), tamanioGabinetePedido, tipoCoolerPedido,
                    enteroPositivo(args, "wattsMinimos"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("", e.getMessage());
        }

        AggregatedResult result = catalogo.getLastResult();
        if (result == null || result.productos() == null) {
            return ToolResult.error("", "No hay catálogo cargado todavía. Corré un scraping primero.");
        }

        PcBuild build = pcBuilder.armar(result.productos(), presupuesto, conGpu, excluir, gamaPedida, prefs,
                usoPedido);
        return ToolResult.ok("", PcBuildJson.toJson(build).toString());
    }

    /**
     * Un piso ausente es {@code null} ("no pedido"); uno presente tiene que
     * ser un entero positivo. {@link PreferenciasDeArmado} rechaza el 0 a
     * propósito — un filtro que no filtra no es un pedido.
     */
    private static Integer enteroPositivo(JsonNode args, String clave) {
        if (!args.hasNonNull(clave)) return null;
        JsonNode n = args.path(clave);
        if (!n.isNumber()) {
            throw new IllegalArgumentException("El parámetro '" + clave + "' tiene que ser un número.");
        }
        return n.asInt();
    }
}
