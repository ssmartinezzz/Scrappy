package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.identity.Sujeto;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.GamaWire;
import ar.scraper.pcs.PcBuild;
import ar.scraper.pcs.PcBuildJson;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
import ar.scraper.pcs.SavedPcsPort;
import ar.scraper.pcs.TechSpecs;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PC builder endpoint + saved PCs, same delegation shape as
 * {@link OutfitsEndpoints}: this class holds no request mapping,
 * {@link ApiController} keeps them and delegates here.
 */
class PcsEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(PcsEndpoints.class);

    private final ScraperService service;
    private final PcBuilder pcBuilder;
    private final SavedPcsPort pcsGuardadas;
    private final PreferenciaArmadorPort preferenciaArmador;
    private final ar.scraper.identity.ActorResolver actorResolver;

    PcsEndpoints(ScraperService service, PcBuilder pcBuilder, SavedPcsPort pcsGuardadas,
                 PreferenciaArmadorPort preferenciaArmador, ar.scraper.identity.ActorResolver actorResolver) {
        this.service = service;
        this.pcBuilder = pcBuilder;
        this.pcsGuardadas = pcsGuardadas;
        this.preferenciaArmador = preferenciaArmador;
        this.actorResolver = actorResolver;
    }

    private String safe(String s) { return s != null ? s : ""; }

    /** {@code gama} is the wire value ("economica"/"media"/"alta"); blank/absent means no tier filter. */
    ResponseEntity<ObjectNode> builder(double presupuesto, boolean conGpu, String excluir, String gama) {
        Gama gamaPedida;
        try {
            gamaPedida = GamaWire.parse(gama);
        } catch (IllegalArgumentException e) {
            ObjectNode resp = JsonNodeFactory.instance.objectNode();
            resp.put("ok", false);
            resp.put("mensaje", "gama inválida: " + gama);
            return ResponseEntity.badRequest().body(resp);
        }

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> excluirUrls = (excluir == null || excluir.isBlank())
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        PcBuild build = pcBuilder.armar(r.productos(), presupuesto, conGpu, excluirUrls, gamaPedida);
        return ResponseEntity.ok(PcBuildJson.toJson(build));
    }

    // ─── Preferencia del armador ────────────────────────────────────────────

    ResponseEntity<ObjectNode> getPreferencia() {
        Optional<PreferenciaArmador> pref = preferenciaArmador.cargar(Sujeto.de(actorResolver));
        if (pref.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(preferenciaJson(pref.get()));
    }

    ResponseEntity<ObjectNode> putPreferencia(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        Object gamaRaw = body.get("gama");
        Gama gama;
        try {
            gama = GamaWire.parse(gamaRaw != null ? String.valueOf(gamaRaw) : null);
        } catch (IllegalArgumentException e) {
            resp.put("ok", false);
            resp.put("mensaje", "gama inválida: " + gamaRaw);
            return ResponseEntity.badRequest().body(resp);
        }
        if (gama == null) {
            resp.put("ok", false);
            resp.put("mensaje", "gama es obligatoria");
            return ResponseEntity.badRequest().body(resp);
        }
        Double presupuesto = body.get("presupuesto") != null ? asDouble(body.get("presupuesto")) : null;
        boolean conGpu = Boolean.parseBoolean(String.valueOf(body.getOrDefault("conGpu", false)));
        PreferenciaArmador preferencia = new PreferenciaArmador(gama, presupuesto, conGpu);
        preferenciaArmador.guardar(Sujeto.de(actorResolver), preferencia);
        return ResponseEntity.ok(preferenciaJson(preferencia));
    }

    private ObjectNode preferenciaJson(PreferenciaArmador p) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("gama", GamaWire.wire(p.gama()));
        if (p.presupuesto() != null) json.put("presupuesto", p.presupuesto()); else json.putNull("presupuesto");
        json.put("conGpu", p.conGpu());
        return json;
    }

    // ─── PCs guardadas ───────────────────────────────────────────────────────

    ResponseEntity<ObjectNode> savePc(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        try {
            String nombre = String.valueOf(body.getOrDefault("nombre", "PC")).trim();
            double presupuesto = asDouble(body.get("presupuesto"));
            boolean conGpu = Boolean.parseBoolean(String.valueOf(body.getOrDefault("conGpu", false)));
            double totalEstimado = asDouble(body.get("totalEstimado"));
            List<PcPick> picks = convertirPicks(body.get("picks"));
            Object gamaRaw = body.get("gama");
            Gama gama;
            try {
                gama = GamaWire.parse(gamaRaw != null ? String.valueOf(gamaRaw) : null);
            } catch (IllegalArgumentException e) {
                resp.put("ok", false);
                resp.put("mensaje", "gama inválida: " + gamaRaw);
                return ResponseEntity.badRequest().body(resp);
            }
            int id = pcsGuardadas.guardarPc(Sujeto.de(actorResolver), nombre, picks, presupuesto, conGpu,
                    totalEstimado, gama);
            if (id < 0) {
                resp.put("ok", false);
                resp.put("mensaje", "No se pudo guardar el PC");
                return ResponseEntity.internalServerError().body(resp);
            }
            resp.put("ok", true);
            resp.put("id", id);
            resp.put("nombre", nombre);
            resp.put("totalEstimado", totalEstimado);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            LOG.warn("[API] savePc error: {}", e.getMessage());
            resp.put("ok", false);
            resp.put("mensaje", e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    /** Un pick sin url se descarta — sin ella el pick no apunta a nada (mismo criterio que {@code saveOutfit}). */
    private List<PcPick> convertirPicks(Object picksRaw) {
        List<PcPick> picks = new ArrayList<>();
        if (!(picksRaw instanceof List<?> lista)) return picks;
        for (Object o : lista) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object urlRaw = m.get("url");
            String url = urlRaw != null ? String.valueOf(urlRaw) : "";
            if (url.isBlank() || "null".equals(url)) continue;
            Object specsRaw = m.get("specs");
            TechSpecs specs = specsRaw instanceof Map<?, ?> s
                    ? new TechSpecs(
                        safeStr(s.get("socket")), safeStr(s.get("ddr")), safeStr(s.get("formFactor")),
                        asInt(s.get("watts")), asInt(s.get("capacidadGb")), safeStr(s.get("tipoMemoria")))
                    : TechSpecs.EMPTY;
            picks.add(new PcPick(
                    safeStr(m.get("slot")), safeStr(m.get("sitio")), safeStr(m.get("nombre")),
                    asDouble(m.get("precio")), url, safeStr(m.get("img")), safeStr(m.get("marca")), specs));
        }
        return picks;
    }

    private String safeStr(Object o) { return o != null ? String.valueOf(o) : ""; }

    private double asDouble(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0.0; }
    }

    private int asInt(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) {
            try { return (int) Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException ignored) { return 0; }
        }
    }

    ResponseEntity<Object> getSavedPcs() {
        return ResponseEntity.ok(pcsGuardadas.obtenerPcsGuardadas(Sujeto.de(actorResolver)));
    }

    ResponseEntity<ObjectNode> deleteSavedPc(int id) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        // 404 cubre "no existe" Y "es de otro usuario": distinguirlos confirmaría
        // que existe una fila de otro usuario.
        boolean ok = pcsGuardadas.eliminarPcGuardada(Sujeto.de(actorResolver), id);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "PC eliminado" : "PC no encontrado");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    ResponseEntity<ObjectNode> renameSavedPc(int id, Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        if (nombre.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "nombre es obligatorio");
            return ResponseEntity.badRequest().body(resp);
        }
        boolean ok = pcsGuardadas.renombrarPc(Sujeto.de(actorResolver), id, nombre);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "PC renombrado" : "PC no encontrado");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }
}
