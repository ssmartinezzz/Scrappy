package ar.scraper.web;

import ar.scraper.scrape.ScraperStatus;

import ar.scraper.indices.Deflactor;
import ar.scraper.indices.DeflactorPorRubro;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndiceService;
import ar.scraper.indices.PuntoIndice;
import ar.scraper.indices.ResumenIndice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;

/**
 * Financing presets ("¿conviene en cuotas?"), the per-product buy recommendation
 * and the macro indices feed (IPC + USD oficial).
 *
 * <p>Endpoints mirroring /api/sitios + /api/config shapes (ADR-5 of
 * financing-buy-signal design). Activate/edit/delete of the active preset
 * trigger a SYNCHRONOUS in-memory recompute via ScraperService — no
 * async/background job, since this is cheap O(n) arithmetic, not a
 * subprocess call like MlEnricher/PythonRunner.</p>
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class FinanciacionEndpoints {

    private final ScraperService service;
    private final IndiceService indiceService;
    private final ar.scraper.financiacion.PresetPort presets;
    private final ar.scraper.catalog.HistorialPort historial;
    private final ar.scraper.catalog.ProductPort productos;
    private final ar.scraper.aggregator.ResultAggregator aggregator;

    FinanciacionEndpoints(ScraperService service,
                          IndiceService indiceService,
                          ar.scraper.financiacion.PresetPort presets,
                          ar.scraper.catalog.HistorialPort historial,
                          ar.scraper.catalog.ProductPort productos,
                          ar.scraper.aggregator.ResultAggregator aggregator) {
        this.service = service;
        this.indiceService = indiceService;
        this.presets = presets;
        this.historial = historial;
        this.productos = productos;
        this.aggregator = aggregator;
    }

    ResponseEntity<ObjectNode> listarPresets() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("presets");
        for (var preset : presets.listarPresets()) {
            ObjectNode n = arr.addObject();
            n.put("id",         preset.id());
            n.put("label",      preset.label());
            n.put("recargoPct", preset.recargoPct());
            n.put("cuotas",     preset.cuotas());
            n.put("activo",     preset.activo());
        }
        var activo = presets.cargarPresetActivo();
        if (activo.isPresent()) {
            ObjectNode a = root.putObject("activo");
            a.put("id",         activo.get().id());
            a.put("label",      activo.get().label());
            a.put("recargoPct", activo.get().recargoPct());
            a.put("cuotas",     activo.get().cuotas());
            a.put("activo",     true);
        } else {
            root.putNull("activo");
        }
        return ResponseEntity.ok(root);
    }

    ResponseEntity<ObjectNode> crearPreset(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (service.getStatus() == ScraperStatus.RUNNING) {
            resp.put("ok", false);
            resp.put("mensaje", "Hay un scraping en curso. Esperá a que termine.");
            return ResponseEntity.status(409).body(resp);
        }
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        Double recargoPct = parseDoubleOrNull(body.get("recargoPct"));
        Integer cuotas = parseIntOrNull(body.get("cuotas"));

        if (label.isBlank() || recargoPct == null || recargoPct < 0 || cuotas == null || cuotas <= 0) {
            resp.put("ok", false);
            resp.put("mensaje", "label, recargoPct (>=0) y cuotas (>0) son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }

        int id = presets.crearPreset(label, recargoPct, cuotas);
        if (id < 0) {
            resp.put("ok", false);
            resp.put("mensaje", "No se pudo crear el preset");
            return ResponseEntity.badRequest().body(resp);
        }
        resp.put("ok", true);
        resp.put("mensaje", "Preset creado");
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> activarPreset(int id) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (service.getStatus() == ScraperStatus.RUNNING) {
            resp.put("ok", false);
            resp.put("mensaje", "Hay un scraping en curso. Esperá a que termine.");
            return ResponseEntity.status(409).body(resp);
        }
        boolean ok = presets.activarPreset(id);
        if (!ok) {
            resp.put("ok", false);
            resp.put("mensaje", "Preset no encontrado");
            return ResponseEntity.status(404).body(resp);
        }
        service.recomputarFinanciacion(aggregator);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> editarPreset(int id, Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (service.getStatus() == ScraperStatus.RUNNING) {
            resp.put("ok", false);
            resp.put("mensaje", "Hay un scraping en curso. Esperá a que termine.");
            return ResponseEntity.status(409).body(resp);
        }
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        Double recargoPct = parseDoubleOrNull(body.get("recargoPct"));
        Integer cuotas = parseIntOrNull(body.get("cuotas"));

        if (label.isBlank() || recargoPct == null || recargoPct < 0 || cuotas == null || cuotas <= 0) {
            resp.put("ok", false);
            resp.put("mensaje", "label, recargoPct (>=0) y cuotas (>0) son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }

        // Detectar si el preset editado es el activo ANTES de editar — editar
        // no cambia el estado activo, solo label/recargoPct/cuotas.
        boolean eraActivo = presets.cargarPresetActivo()
                .map(p -> p.id() == id).orElse(false);

        boolean ok = presets.editarPreset(id, label, recargoPct, cuotas);
        if (!ok) {
            resp.put("ok", false);
            resp.put("mensaje", "Preset no encontrado o datos inválidos");
            return ResponseEntity.badRequest().body(resp);
        }

        if (eraActivo) service.recomputarFinanciacion(aggregator);
        resp.put("ok", true);
        resp.put("mensaje", "Preset actualizado");
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> eliminarPreset(int id) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (service.getStatus() == ScraperStatus.RUNNING) {
            resp.put("ok", false);
            resp.put("mensaje", "Hay un scraping en curso. Esperá a que termine.");
            return ResponseEntity.status(409).body(resp);
        }
        boolean eraActivo = presets.cargarPresetActivo()
                .map(p -> p.id() == id).orElse(false);

        boolean borrado = presets.eliminarPreset(id);
        if (!borrado) {
            resp.put("ok", false);
            resp.put("mensaje", "Preset no encontrado");
            return ResponseEntity.status(404).body(resp);
        }

        if (eraActivo) service.recomputarFinanciacion(aggregator);
        resp.put("ok", true);
        resp.put("mensaje", "Preset eliminado");
        return ResponseEntity.ok(resp);
    }

    private Double parseDoubleOrNull(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return null; }
    }

    private Integer parseIntOrNull(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(String.valueOf(v).split("\\.")[0]); }
        catch (Exception e) { return null; }
    }

    // ─── Recomendacion de compra ─────────────────────────────────────────────────

    ResponseEntity<Object> recomendacion(String url) {
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root   = MAPPER.createObjectNode();
        var hist = historial.getHistorialPrecios(url);
        if (hist == null || hist.isEmpty()) {
            root.put("senal",   "sin_datos");
            root.put("mensaje", "Sin historial suficiente para analizar");
            return ResponseEntity.ok(root);
        }
        hist.sort(java.util.Comparator.comparing(h -> h.fecha()));
        double precioActual = hist.get(hist.size()-1).precio();
        double precioMin    = hist.stream().mapToDouble(h -> h.precio()).min().orElse(precioActual);
        double precioMax    = hist.stream().mapToDouble(h -> h.precio()).max().orElse(precioActual);
        double rango        = precioMax - precioMin;
        int    puntoAntiguo = Math.max(0, hist.size() - 13);
        double precioAntiguo  = hist.get(puntoAntiguo).precio();
        LocalDate desde = LocalDate.parse(hist.get(puntoAntiguo).fecha());
        LocalDate hasta = LocalDate.parse(hist.get(hist.size() - 1).fecha());
        Indice indice = DeflactorPorRubro.resolver(
                productos.obtenerProducto(url).map(ar.scraper.model.Product::rubro).orElse(null));
        Deflactor deflactor = indiceService.deflactor(indice, desde, hasta);
        double precioAjustado = precioAntiguo * deflactor.factor();
        double cambioReal = precioAjustado > 0
            ? (precioActual - precioAjustado) / precioAjustado * 100.0 : 0.0;
        double pctDelMin  = rango > 0 ? (precioActual - precioMin) / rango * 100.0 : 50.0;
        String tendencia  = "estable";
        if (hist.size() >= 4) {
            double p1 = hist.get(hist.size()-4).precio();
            double p2 = hist.get(hist.size()-1).precio();
            double cambioNominal = p1 > 0 ? (p2 - p1) / p1 * 100.0 : 0;
            if (cambioNominal >  5.0) tendencia = "subiendo";
            else if (cambioNominal < -5.0) tendencia = "bajando";
        }
        String senal, emoji, mensaje;
        int    scoreCompra;
        if (pctDelMin <= 10.0) {
            senal = "comprar_ahora"; emoji = "🔥"; scoreCompra = 95;
            mensaje = "Minimo historico, nunca estuvo mas barato";
        } else if (cambioReal < -8.0 && "bajando".equals(tendencia)) {
            senal = "muy_buen_momento"; emoji = "✅"; scoreCompra = 85;
            mensaje = String.format("Bajo %.0f%% en terminos reales en los ultimos meses", Math.abs(cambioReal));
        } else if (cambioReal < -3.0) {
            senal = "buen_momento"; emoji = "👍"; scoreCompra = 70;
            mensaje = String.format("Precio real cayo %.0f%%, mas barato ajustado por %s", Math.abs(cambioReal), indice.etiqueta());
        } else if (cambioReal > 10.0 && "subiendo".equals(tendencia)) {
            senal = "esperar"; emoji = "⚠"; scoreCompra = 20;
            mensaje = String.format("Subio %.0f%% mas que el %s, puede bajar", cambioReal, indice.etiqueta());
        } else if (pctDelMin >= 80.0) {
            senal = "caro"; emoji = "❌"; scoreCompra = 15;
            mensaje = "Precio en maximo historico, esperar mejor momento";
        } else {
            senal = "precio_normal"; emoji = "📊"; scoreCompra = 50;
            mensaje = "Precio en rango habitual sin senal fuerte";
        }
        root.put("senal",      senal);
        root.put("emoji",      emoji);
        root.put("mensaje",    mensaje);
        root.put("scoreCompra", scoreCompra);
        root.put("cambioReal",  Math.round(cambioReal * 10.0) / 10.0);
        root.put("pctDelMin",   (int) Math.round(pctDelMin));
        root.put("precioMin",   precioMin);
        root.put("precioMax",   precioMax);
        root.put("tendencia",   tendencia);
        root.put("indice",      indice.name());
        root.put("confianza",   deflactor.confianza().name().toLowerCase());
        root.put("diasExtrapolados", deflactor.diasExtrapolados());
        ResumenIndice ipc = indiceService.resumen(Indice.IPC);
        root.put("inflacionMensual",    ipc.variacionMensual() != null ? ipc.variacionMensual() : 0.0);
        root.put("inflacionInteranual", ipc.variacionInteranual() != null ? ipc.variacionInteranual() : 0.0);
        root.put("puntosHistorial",     hist.size());
        return ResponseEntity.ok(root);
    }

    // ─── Índices macro (IPC + USD oficial) ────────────────────────────────────────

    ResponseEntity<Object> indices() {
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = MAPPER.createObjectNode();
        root.set("ipc", resumenJson(MAPPER, indiceService.resumen(Indice.IPC)));
        root.set("usd", resumenJson(MAPPER, indiceService.resumen(Indice.USD_OFICIAL)));
        root.put("actualizado", indiceService.ultimaActualizacion());
        return ResponseEntity.ok(root);
    }

    private static ObjectNode resumenJson(com.fasterxml.jackson.databind.ObjectMapper mapper, ResumenIndice r) {
        var n = mapper.createObjectNode();
        n.put("indice",              r.indice().name());
        n.put("ultimoValor",         r.ultimoValor());
        n.put("ultimaFecha",         r.ultimaFecha() != null ? r.ultimaFecha().toString() : null);
        n.put("variacionMensual",    r.variacionMensual());
        n.put("variacionInteranual", r.variacionInteranual());
        n.put("variacion3m",        r.variacion3m());
        n.put("confianza",          r.confianza().name().toLowerCase());
        var ultimos = n.putArray("ultimos");
        for (PuntoIndice p : r.ultimos()) {
            var pn = ultimos.addObject();
            pn.put("fecha", p.fecha().toString());
            pn.put("valor", p.valor());
        }
        return n;
    }
}
