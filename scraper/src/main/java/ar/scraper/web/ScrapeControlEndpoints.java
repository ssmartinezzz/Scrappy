package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import ar.scraper.web.dto.ScrapeDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run control, site registry and price-range config. Mappings live in {@link ApiController}. */
class ScrapeControlEndpoints {

    private final ScraperService service;
    private final ScraperConfig config;

    ScrapeControlEndpoints(ScraperService service, ScraperConfig config) {
        this.service = service;
        this.config = config;
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Status>> status() {
        return ResponseEntity.ok(ApiResponse.ok(statusDto()));
    }

    /** What {@code /api/status} puts inside {@code data}; the event stream's snapshot reuses it. */
    ScrapeDtos.Status statusDto() {
        var b = ScrapeDtos.Status.builder()
                .status(service.getStatus().name())
                .mensaje(service.getStatusMsg());
        var lr = service.getLastResult();
        b.tieneData(lr != null);
        if (lr != null) {
            b.total(lr.productos().size());
            b.mlRefinadas(service.getUltimasCategoriasRefinadas());
            b.mlModeloActivo(new java.io.File("_models/text_classifier.pkl").exists());
            var st = lr.statsPorSitio();
            if (st != null && !st.isEmpty()) {
                Map<String, ScrapeDtos.ExtractionStats> stats = new LinkedHashMap<>();
                st.forEach((sitio, s) ->
                        stats.put(sitio, new ScrapeDtos.ExtractionStats(s.total(), s.valid(), s.misses())));
                b.extractionStats(stats);
            }
        }

        // `run` is additive: `status` stays IDLE|RUNNING|DONE|ERROR (a cancelled run reports
        // DONE) so the CLI contract in cli/core/rest.py does not gain an enum value.
        var rs = service.getRunState();
        if (rs != null) {
            b.run(new ScrapeDtos.RunInfo(rs.scrapeUuid().toString(), rs.startedAt().toString(),
                    service.estaCancelado()));
        }

        ScraperService.ProgressData pd = service.getProgressData();
        if (pd != null) {
            List<ScrapeDtos.SitioProgreso> sitios = new ArrayList<>();
            for (var sp : pd.sitios()) {
                sitios.add(ScrapeDtos.SitioProgreso.desde(
                        sp.nombre(), sp.estado().name(), sp.productos(), sp.duracionMs(), sp.error()));
            }
            b.progreso(new ScrapeDtos.Progreso(pd.total(), pd.completados(),
                    pd.productosAcumulados(), sitios));
        }
        return b.build();
    }

    /** Informs only: detecting an interrupted run does not resume it. */
    ResponseEntity<ApiResponse<ScrapeDtos.Interrumpida>> interrumpida() {
        var det = service.getInterrumpida();
        var b = ScrapeDtos.Interrumpida.builder().hayInterrumpida(det != null);
        if (det != null) {
            // Skipped sites are named on purpose: a site removed from the registry
            // since the crash cannot be resumed, and dropping it silently is worse.
            b.uuid(det.uuid().toString())
                    .startedAt(det.startedAt().toString())
                    .soloFaltaLaPasadaFinal(det.soloFaltaLaPasadaFinal())
                    .atendidos(det.atendidos())
                    .pendientes(det.pendientes())
                    .salteados(det.salteados());
        }
        return ResponseEntity.ok(ApiResponse.ok(b.build()));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Retomar>> retomar() {
        boolean ok = service.reanudar();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Retomar(ok, ok
                ? "Retomando la corrida interrumpida"
                : "No hay corrida interrumpida, o ya hay un scraping en curso")));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Descartar>> descartar() {
        int cerradas = service.descartarInterrumpidas();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Descartar(cerradas, cerradas > 0
                ? cerradas + " corrida(s) interrumpida(s) descartada(s). El catálogo queda como está."
                : "No había ninguna corrida interrumpida que descartar")));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Cancelar>> cancelar() {
        boolean ok = service.cancelar();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Cancelar(ok, ok
                ? "Cancelando: se deja de esperar sitios y el catálogo queda como está"
                : "No hay ningún scraping en curso")));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Iniciar>> scrape(Double precioMin, Double precioMax, Double precio,
                                                            List<String> sitios, boolean forceRetrain) {
        if (precioMin != null) config.setPrecioMinimo(precioMin);
        if (precioMax != null) config.setPrecioMaximo(precioMax);
        if (precio    != null) config.setPrecioMaximo(precio);

        Set<String> seleccion = (sitios != null && !sitios.isEmpty())
                ? new HashSet<>(sitios) : null;

        boolean ok = service.iniciarScraping(seleccion, forceRetrain);
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Iniciar(ok,
                ok ? "Scraping iniciado" : "Ya hay un scraping en curso")));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.Sitios>> getSitios() {
        List<ScrapeDtos.SitioBase> base = new ArrayList<>();
        for (var s : config.getSitiosActivos()) {
            base.add(new ScrapeDtos.SitioBase(s.nombre(), s.url(), "config", s.rubro()));
        }
        List<ScrapeDtos.SitioExtra> extras = new ArrayList<>();
        for (var s : service.getSitiosExtras()) {
            extras.add(new ScrapeDtos.SitioExtra(s.nombre(), s.url(), s.plataforma(), "dinamico"));
        }
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Sitios(base, extras,
                config.getPrecioMinimo(), config.getPrecioMaximo(), config.getMoneda())));
    }

    ResponseEntity<ApiResponse<OpResult>> agregarSitio(Map<String, String> body) {
        String nombre     = body.getOrDefault("nombre", "").trim();
        String url        = body.getOrDefault("url", "").trim();
        String plataforma = body.getOrDefault("plataforma", "tiendanube").trim();
        if (nombre.isBlank() || url.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "nombre y url son obligatorios");
        }
        if (!url.startsWith("http")) url = "https://" + url;
        service.agregarSitio(nombre, url, plataforma);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(true,
                "Sitio '" + nombre + "' agregado. Corré el scraper para incluirlo.")));
    }

    ResponseEntity<ApiResponse<OpResult>> eliminarSitio(String nombre) {
        boolean ok = service.eliminarSitio(nombre);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(ok,
                ok ? "Sitio eliminado" : "Sitio no encontrado")));
    }

    ResponseEntity<ApiResponse<ScrapeDtos.ConfigResult>> updateConfig(Map<String, Object> body) {
        var resp = new ScrapeDtos.ConfigResult();
        if (body.containsKey("precioMinimo")) {
            double v = Double.parseDouble(body.get("precioMinimo").toString());
            config.setPrecioMinimo(v);
            resp.setPrecioMinimo(v);
        }
        if (body.containsKey("precioMaximo")) {
            double v = Double.parseDouble(body.get("precioMaximo").toString());
            config.setPrecioMaximo(v);
            resp.setPrecioMaximo(v);
        }
        resp.setOk(true);
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }
}
