package ar.scraper.web;

import ar.scraper.scrape.ScraperStatus;

import ar.scraper.indices.Deflactor;
import ar.scraper.indices.DeflactorPorRubro;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndiceService;
import ar.scraper.indices.PuntoIndice;
import ar.scraper.indices.ResumenIndice;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.FinanciacionDtos;
import ar.scraper.web.dto.OpResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Financing presets, the per-product buy recommendation and the macro indices feed.
 * Activate/edit/delete of the active preset trigger a SYNCHRONOUS in-memory recompute
 * (cheap O(n) arithmetic, not a subprocess).
 */
@RestController
@RequestMapping("/api")
public class FinanciacionController {

    private final ScraperService service;
    private final IndiceService indiceService;
    private final ar.scraper.financiacion.PresetPort presets;
    private final ar.scraper.catalog.HistorialPort historial;
    private final ar.scraper.catalog.ProductPort productos;
    private final ar.scraper.aggregator.ResultAggregator aggregator;

    public FinanciacionController(ScraperService service,
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

    @GetMapping("/financiacion/presets")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Presets>> listarPresets() {
        List<FinanciacionDtos.Preset> lista = new ArrayList<>();
        for (var preset : presets.listarPresets()) {
            lista.add(new FinanciacionDtos.Preset(preset.id(), preset.label(),
                    preset.recargoPct(), preset.cuotas(), preset.activo()));
        }
        var activo = presets.cargarPresetActivo()
                .map(a -> new FinanciacionDtos.Preset(a.id(), a.label(), a.recargoPct(), a.cuotas(), true))
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.ok(new FinanciacionDtos.Presets(lista, activo)));
    }

    @PostMapping("/financiacion/presets")
    public ResponseEntity<ApiResponse<OpResult>> crearPreset(@RequestBody Map<String, Object> body) {
        rechazarSiHayScraping();
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        Double recargoPct = parseDoubleOrNull(body.get("recargoPct"));
        Integer cuotas = parseIntOrNull(body.get("cuotas"));
        validar(label, recargoPct, cuotas);

        int id = presets.crearPreset(label, recargoPct, cuotas);
        if (id < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "No se pudo crear el preset");
        }
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Preset creado")));
    }

    @PutMapping("/financiacion/presets/{id}/activar")
    public ResponseEntity<ApiResponse<OpResult>> activarPreset(@PathVariable int id) {
        rechazarSiHayScraping();
        if (!presets.activarPreset(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "Preset no encontrado");
        }
        service.recomputarFinanciacion(aggregator);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    @PutMapping("/financiacion/presets/{id}")
    public ResponseEntity<ApiResponse<OpResult>> editarPreset(@PathVariable int id, @RequestBody Map<String, Object> body) {
        rechazarSiHayScraping();
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        Double recargoPct = parseDoubleOrNull(body.get("recargoPct"));
        Integer cuotas = parseIntOrNull(body.get("cuotas"));
        validar(label, recargoPct, cuotas);

        // Editing does not change which preset is active, only its label/recargoPct/cuotas.
        boolean eraActivo = presets.cargarPresetActivo()
                .map(p -> p.id() == id).orElse(false);

        if (!presets.editarPreset(id, label, recargoPct, cuotas)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "Preset no encontrado o datos inválidos");
        }

        if (eraActivo) service.recomputarFinanciacion(aggregator);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Preset actualizado")));
    }

    @DeleteMapping("/financiacion/presets/{id}")
    public ResponseEntity<ApiResponse<OpResult>> eliminarPreset(@PathVariable int id) {
        rechazarSiHayScraping();
        boolean eraActivo = presets.cargarPresetActivo()
                .map(p -> p.id() == id).orElse(false);

        if (!presets.eliminarPreset(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "Preset no encontrado");
        }

        if (eraActivo) service.recomputarFinanciacion(aggregator);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true, "Preset eliminado")));
    }

    private void rechazarSiHayScraping() {
        if (service.getStatus() == ScraperStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "scrape_en_curso",
                    "Hay un scraping en curso. Esperá a que termine.");
        }
    }

    private static void validar(String label, Double recargoPct, Integer cuotas) {
        if (label.isBlank() || recargoPct == null || recargoPct < 0 || cuotas == null || cuotas <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "label, recargoPct (>=0) y cuotas (>0) son obligatorios");
        }
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

    @GetMapping("/recomendacion")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Recomendacion>> recomendacion(@RequestParam String url) {
        var root = new FinanciacionDtos.Recomendacion();
        var hist = historial.getHistorialPrecios(url);
        if (hist == null || hist.isEmpty()) {
            root.setSenal("sin_datos");
            root.setMensaje("Sin historial suficiente para analizar");
            return ResponseEntity.ok(ApiResponse.ok(root));
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
        ResumenIndice ipc = indiceService.resumen(Indice.IPC);
        root.setSenal(senal);
        root.setEmoji(emoji);
        root.setMensaje(mensaje);
        root.setScoreCompra(scoreCompra);
        root.setCambioReal(Math.round(cambioReal * 10.0) / 10.0);
        root.setPctDelMin((int) Math.round(pctDelMin));
        root.setPrecioMin(precioMin);
        root.setPrecioMax(precioMax);
        root.setTendencia(tendencia);
        root.setIndice(indice.name());
        root.setConfianza(deflactor.confianza().name().toLowerCase());
        root.setDiasExtrapolados(deflactor.diasExtrapolados());
        root.setInflacionMensual(ipc.variacionMensual() != null ? ipc.variacionMensual() : 0.0);
        root.setInflacionInteranual(ipc.variacionInteranual() != null ? ipc.variacionInteranual() : 0.0);
        root.setPuntosHistorial(hist.size());
        return ResponseEntity.ok(ApiResponse.ok(root));
    }

    @GetMapping("/indices")
    public ResponseEntity<ApiResponse<FinanciacionDtos.Indices>> indices() {
        return ResponseEntity.ok(ApiResponse.ok(new FinanciacionDtos.Indices(
                resumen(indiceService.resumen(Indice.IPC)),
                resumen(indiceService.resumen(Indice.USD_OFICIAL)),
                indiceService.ultimaActualizacion())));
    }

    private static FinanciacionDtos.Resumen resumen(ResumenIndice r) {
        List<FinanciacionDtos.Punto> ultimos = new ArrayList<>();
        for (PuntoIndice p : r.ultimos()) {
            ultimos.add(new FinanciacionDtos.Punto(p.fecha().toString(), p.valor()));
        }
        return new FinanciacionDtos.Resumen(r.indice().name(), r.ultimoValor(),
                r.ultimaFecha() != null ? r.ultimaFecha().toString() : null,
                r.variacionMensual(), r.variacionInteranual(), r.variacion3m(),
                r.confianza().name().toLowerCase(), ultimos);
    }
}
