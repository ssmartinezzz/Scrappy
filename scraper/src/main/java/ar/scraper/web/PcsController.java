package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.security.Sujeto;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.GamaWire;
import ar.scraper.pcs.PcBuild;
import ar.scraper.json.PcBuildJson;
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
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PcsController {

    private final ScraperService service;
    private final PcBuilder pcBuilder;
    private final SavedPcsPort pcsGuardadas;
    private final PreferenciaArmadorPort preferenciaArmador;
    private final ar.scraper.security.ActorResolver actorResolver;

    ResponseEntity<ApiResponse<ObjectNode>> builder(double presupuesto, boolean conGpu, String excluir, String gama) {
        return builder(presupuesto, conGpu, excluir, gama, "", "", "", "", null, null);
    }

    /** Wire values (blank/absent = not requested); */
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

    @GetMapping("/pcs/builder")
    public ResponseEntity<ApiResponse<ObjectNode>> builder(@RequestParam(defaultValue = "0") double presupuesto,
            @RequestParam(defaultValue = "false") boolean conGpu,
            @RequestParam(defaultValue = "") String excluir,
            @RequestParam(defaultValue = "") String gama,
            @RequestParam(defaultValue = "") String ddr,
            @RequestParam(defaultValue = "") String marcaCpu,
            @RequestParam(defaultValue = "") String marcaGpu,
            @RequestParam(defaultValue = "") String tipoAlmacenamiento,
            @RequestParam(required = false) Boolean ramDual,
            @RequestParam(required = false) Boolean wifi,
            @RequestParam(required = false) Integer capacidadMinimaGb,
            @RequestParam(defaultValue = "") String tamanioGabinete,
            @RequestParam(defaultValue = "") String tipoCooler,
            @RequestParam(required = false) Integer wattsMinimos,
            @RequestParam(defaultValue = "") String uso) {
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

        Set<String> excluirUrls = Params.setOrEmpty(excluir);

        PcBuild build = pcBuilder.armar(r.productos(), presupuesto, conGpu, excluirUrls, gamaPedida, prefs, usoPedido);
        return ResponseEntity.ok(ApiResponse.ok(PcBuildJson.toJson(build)));
    }

    @GetMapping("/pcs/preferencia")
    public ResponseEntity<ApiResponse<PcsDtos.Preferencia>> getPreferencia() {
        Optional<PreferenciaArmador> pref = preferenciaArmador.cargar(Sujeto.de(actorResolver));
        if (pref.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(preferencia(pref.get())));
    }

    @PutMapping("/pcs/preferencia")
    public ResponseEntity<ApiResponse<PcsDtos.Preferencia>> putPreferencia(@RequestBody Map<String, Object> body) {
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

    /**
     * An absent floor is "not requested", not zero: a {@code 0} would reach PreferenciasDeArmado,
     * which rejects it.
     */
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

    @PostMapping("/pcs/save")
    public ResponseEntity<ApiResponse<PcsDtos.Guardada>> savePc(@RequestBody Map<String, Object> body) {
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
                    ? TechSpecs.builder()
                        .socket(safeStr(s.get("socket"))).ddr(safeStr(s.get("ddr")))
                        .formFactor(safeStr(s.get("formFactor")))
                        .watts(asInt(s.get("watts"))).capacidadGb(asInt(s.get("capacidadGb")))
                        .tipoMemoria(safeStr(s.get("tipoMemoria"))).build()
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

    @GetMapping("/pcs/saved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSavedPcs() {
        return ResponseEntity.ok(ApiResponse.ok(pcsGuardadas.obtenerPcsGuardadas(Sujeto.de(actorResolver))));
    }

    @DeleteMapping("/pcs/saved/{id}")
    public ResponseEntity<ApiResponse<OpResult>> deleteSavedPc(@PathVariable int id) {
        // 404 covers "does not exist" AND "belongs to someone else": telling them apart would
        // confirm another user's row exists.
        if (!pcsGuardadas.eliminarPcGuardada(Sujeto.de(actorResolver), id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "PC no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "PC eliminado")));
    }

    @PatchMapping("/pcs/saved/{id}/nombre")
    public ResponseEntity<ApiResponse<OpResult>> renameSavedPc(@PathVariable int id,
            @RequestBody Map<String, Object> body) {
        String nombre = Params.nombreObligatorio(body);
        if (!pcsGuardadas.renombrarPc(Sujeto.de(actorResolver), id, nombre)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "PC no encontrado");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "PC renombrado")));
    }
}
