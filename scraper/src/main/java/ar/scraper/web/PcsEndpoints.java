package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.pcs.PcBuild;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.pcs.PcPick;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PC builder endpoint, same delegation shape as {@link OutfitsEndpoints}'s
 * supplement builder: this class holds no request mapping, {@link ApiController}
 * keeps it and delegates here.
 */
class PcsEndpoints {

    private final ScraperService service;
    private final PcBuilder pcBuilder;

    PcsEndpoints(ScraperService service, PcBuilder pcBuilder) {
        this.service = service;
        this.pcBuilder = pcBuilder;
    }

    private String safe(String s) { return s != null ? s : ""; }

    ResponseEntity<ObjectNode> builder(double presupuesto, boolean conGpu, String excluir) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> excluirUrls = (excluir == null || excluir.isBlank())
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        PcBuild build = pcBuilder.armar(r.productos(), presupuesto, conGpu, excluirUrls);

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
            specs.put("socket",      pick.specs().socket());
            specs.put("ddr",         pick.specs().ddr());
            specs.put("formFactor",  pick.specs().formFactor());
            specs.put("watts",       pick.specs().watts());
            specs.put("capacidadGb", pick.specs().capacidadGb());
            specs.put("tipoMemoria", pick.specs().tipoMemoria());
        }
        ArrayNode sinStockArr = root.putArray("sinStock");
        build.sinStock().forEach(sinStockArr::add);
        ArrayNode sinCompatibleArr = root.putArray("sinCompatible");
        build.sinCompatible().forEach(sinCompatibleArr::add);
        root.put("presupuesto", build.presupuesto());
        root.put("totalEstimado", build.totalEstimado());
        return ResponseEntity.ok(root);
    }
}
