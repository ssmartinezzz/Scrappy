package ar.scraper.pcs;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializes a {@link PcBuild} to the JSON shape {@code GET /api/pcs/builder}
 * serves — extracted from {@code PcsEndpoints.builder} so the endpoint and
 * {@code agent/ProposePcTool} share one shape (DOC-1 for code).
 */
public class PcBuildJson {

    private static String safe(String s) { return s != null ? s : ""; }

    public static ObjectNode toJson(PcBuild build) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode picksArr = root.putArray("picks");
        for (PcPick pick : build.picks()) {
            ObjectNode n = picksArr.addObject();
            n.put("slot",   pick.slot());
            n.put("sitio",  safe(pick.sitio()));
            n.put("nombre", safe(pick.nombre()));
            n.put("precio", pick.precio());
            n.put("url",    safe(pick.url()));
            n.put("img",    safe(pick.img()));
            n.put("marca",  safe(pick.marca()));
            ObjectNode specs = n.putObject("specs");
            specs.put("socket",             pick.specs().socket());
            specs.put("ddr",                pick.specs().ddr());
            specs.put("formFactor",         pick.specs().formFactor());
            specs.put("watts",              pick.specs().watts());
            specs.put("capacidadGb",        pick.specs().capacidadGb());
            specs.put("tipoMemoria",        pick.specs().tipoMemoria());
            specs.put("gama",               pick.specs().gama().name());
            specs.put("certificacion",      pick.specs().certificacion().name());
            specs.put("velocidadMhz",       pick.specs().velocidadMhz());
            specs.put("tipoAlmacenamiento", pick.specs().tipoAlmacenamiento().name());
            specs.put("marcaChip",          pick.specs().marcaChip());
            specs.put("generacion",         pick.specs().generacion());
            specs.put("tierChipset",        pick.specs().tierChipset());
            specs.put("modulos",            pick.specs().modulos());
            specs.put("wifi",               pick.specs().wifi());
            specs.put("tipoCooler",         pick.specs().tipoCooler().name());
        }
        ArrayNode sinStockArr = root.putArray("sinStock");
        build.sinStock().forEach(sinStockArr::add);
        ArrayNode sinCompatibleArr = root.putArray("sinCompatible");
        build.sinCompatible().forEach(sinCompatibleArr::add);
        root.put("presupuesto", build.presupuesto());
        root.put("totalEstimado", build.totalEstimado());
        ObjectNode mensajes = root.putObject("mensajes");
        build.mensajes().forEach(mensajes::put);
        return root;
    }
}
