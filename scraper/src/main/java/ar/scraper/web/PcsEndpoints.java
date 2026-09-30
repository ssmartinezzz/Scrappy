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
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.PreferenciasWire;
import ar.scraper.pcs.SavedPcsPort;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.Uso;
import ar.scraper.pcs.UsoWire;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import ar.scraper.web.dto.PcsDtos;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/** PC builder endpoint + saved PCs. Mappings live in {@link ApiController}. */
class PcsEndpoints {

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
    ResponseEntity<ApiResponse<ObjectNode>> builder(double presupuesto, boolean conGpu, String excluir, String gama) {
        return builder(presupuesto, conGpu, excluir, gama, "", "", "", "", null, null);
    }

    /** Wire values (blank/absent = not requested); {@code ramDual}/{@code wifi} are null when absent. */
    ResponseEntity<ApiResponse<ObjectNode>> builder(double presupuesto, boolean conGpu, String excluir, String gama,
            String ddr, String marcaCpu, String marcaGpu, String tipoAlmacenamiento,
            Boolean ramDual, Boolean wifi) {
        return builder(presupuesto, conGpu, excluir, gama, ddr, marcaCpu, marcaGpu, tipoAlmacenamiento,
                ramDual, wifi, null, "", "", null);
    }

    /** {@code capacidadMinimaGb}/{@code wattsMinimos} are floors (null = not requested). */
    ResponseEntity<ApiResponse<ObjectNode>> builder(double presupuesto, boolean conGpu, String excluir, String gama,
            String ddr, String marcaCpu, String marcaGpu, String tipoAlmacenamiento,
            Boolean ramDual, Boolean wifi,
            Integer capacidadMinimaGb, String tamanioGabinete, String tipoCooler, Integer wattsMinimos) {
        return builder(presupuesto, conGpu, excluir, gama, ddr, marcaCpu, marcaGpu, tipoAlmacenamiento,
                ramDual, wifi, capacidadMinimaGb, tamanioGabinete, tipoCooler, wattsMinimos, "");
    }

    /** {@code uso} blank/absent means {@link Uso#GAMING}, the default ({@link UsoWire#parse}). */
    ResponseEntity<ApiResponse<ObjectNode>> builder(double presupuesto, boolean conGpu, String excluir, String gama,
            String ddr, String marcaCpu, String marcaGpu, String tipoAlmacenamiento,
            Boolean ramDual, Boolean wifi,
            Integer capacidadMinimaGb, String tamanioGabinete, String tipoCooler, Integer wattsMinimos,
            String uso) {
        Gama gamaPedida;
        PreferenciasDeArmado prefs;
        Uso usoPedido;
        try {
            gamaPedida = GamaWire.parse(gama);
            prefs = PreferenciasWire.parse(ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi,
                    capacidadMinimaGb, tamanioGabinete, tipoCooler, wattsMinimos);
            usoPedido = UsoWire.parse(uso);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", e.getMessage());
        }

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> excluirUrls = StringUtils.isBlank(excluir)
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        PcBuild build = pcBuilder.armar(r.productos(), presupuesto, conGpu, excluirUrls, gamaPedida, prefs, usoPedido);
        // PcBuildJson is a dynamic JSON builder shared with the agent tool, hence ObjectNode.
        return ResponseEntity.ok(ApiResponse.ok(PcBuildJson.toJson(build)));
    }

    ResponseEntity<ApiResponse<PcsDtos.Preferencia>> getPreferencia() {
        Optional<PreferenciaArmador> pref = preferenciaArmador.cargar(Sujeto.de(actorResolver));
        if (pref.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(preferencia(pref.get())));
    }

    ResponseEntity<ApiResponse<PcsDtos.Preferencia>> putPreferencia(Map<String, Object> body) {
        Object gamaRaw = body.get("gama");
        Gama gama;
        PreferenciasDeArmado prefs;
        Uso uso;
        try {
            gama = GamaWire.parse(gamaRaw != null ? String.valueOf(gamaRaw) : null);
            prefs = PreferenciasWire.parse(
                    stringDe(body, "ddr"), stringDe(body, "marcaCpu"), stringDe(body, "marcaGpu"),
                    stringDe(body, "tipoAlmacenamiento"), booleanDe(body, "ramDual"), booleanDe(body, "wifi"),
                    enteroDe(body, "capacidadMinimaGb"), stringDe(body, "tamanioGabinete"),
                    stringDe(body, "tipoCooler"), enteroDe(body, "wattsMinimos"));
            uso = UsoWire.parse(stringDe(body, "uso"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", e.getMessage());
        }
        if (gama == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "gama es obligatoria");
        }
        Double presupuesto = body.get("presupuesto") != null ? asDouble(body.get("presupuesto")) : null;
        boolean conGpu = Boolean.parseBoolean(String.valueOf(body.getOrDefault("conGpu", false)));
        PreferenciaArmador preferencia = new PreferenciaArmador(gama, presupuesto, conGpu, prefs, uso);
        preferenciaArmador.guardar(Sujeto.de(actorResolver), preferencia);
        return ResponseEntity.ok(ApiResponse.ok(preferencia(preferencia)));
    }

    private static String stringDe(Map<String, Object> body, String clave) {
        Object valor = body.get(clave);
        return valor != null ? String.valueOf(valor) : null;
    }

    private static Boolean booleanDe(Map<String, Object> body, String clave) {
        Object valor = body.get(clave);
        return valor != null ? Boolean.parseBoolean(String.valueOf(valor)) : null;
    }

    /** An absent floor is "not requested", not zero: a {@code 0} would reach PreferenciasDeArmado, which rejects it. */
    private static Integer enteroDe(Map<String, Object> body, String clave) {
        Object valor = body.get(clave);
        if (valor == null) return null;
        if (valor instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(valor).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(clave + " inválido: " + valor);
        }
    }

    private PcsDtos.Preferencia preferencia(PreferenciaArmador p) {
        PreferenciasDeArmado prefs = p.preferencias();
        return new PcsDtos.Preferencia(
                GamaWire.wire(p.gama()), p.presupuesto(), p.conGpu(),
                PreferenciasWire.wireDdr(prefs.ddr()),
                PreferenciasWire.wireMarcaCpu(prefs.marcaCpu()),
                PreferenciasWire.wireMarcaGpu(prefs.marcaGpu()),
                PreferenciasWire.wireTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                Boolean.TRUE.equals(prefs.ramDual()), Boolean.TRUE.equals(prefs.wifi()),
                prefs.capacidadMinimaGb(),
                PreferenciasWire.wireTamanioGabinete(prefs.tamanioGabinete()),
                PreferenciasWire.wireTipoCooler(prefs.tipoCooler()),
                prefs.wattsMinimos(), UsoWire.wire(p.uso()));
    }

    ResponseEntity<ApiResponse<PcsDtos.Guardada>> savePc(Map<String, Object> body) {
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "gama inválida: " + gamaRaw);
        }
        int id = pcsGuardadas.guardarPc(Sujeto.de(actorResolver), nombre, picks, presupuesto, conGpu,
                totalEstimado, gama);
        if (id < 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error_interno", "No se pudo guardar el PC");
        }
        return ResponseEntity.ok(ApiResponse.ok(new PcsDtos.Guardada(true, id, nombre, totalEstimado)));
    }

    /** A pick without url is dropped: it would point at nothing (same as {@code saveOutfit}). */
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

    // Rows come from SavedPcsPort as maps; typing them is a persistence-layer change.
    ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSavedPcs() {
        return ResponseEntity.ok(ApiResponse.ok(pcsGuardadas.obtenerPcsGuardadas(Sujeto.de(actorResolver))));
    }

    ResponseEntity<ApiResponse<OpResult>> deleteSavedPc(int id) {
        // 404 covers "does not exist" AND "belongs to someone else": telling them apart would
        // confirm another user's row exists.
        if (!pcsGuardadas.eliminarPcGuardada(Sujeto.de(actorResolver), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "PC no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "PC eliminado")));
    }

    ResponseEntity<ApiResponse<OpResult>> renameSavedPc(int id, Map<String, Object> body) {
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        if (nombre.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "nombre es obligatorio");
        }
        if (!pcsGuardadas.renombrarPc(Sujeto.de(actorResolver), id, nombre)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "PC no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "PC renombrado")));
    }
}
