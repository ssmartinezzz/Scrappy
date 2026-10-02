package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.ScrapeDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScrapeController {

    private final ScraperService service;
    private final ScraperConfig config;
    private final ScrapeStatusView statusView;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ScrapeDtos.Status>> status() {
        return ResponseEntity.ok(ApiResponse.ok(statusView.snapshot()));
    }

    /** Informs only: detecting an interrupted run does not resume it. */
    @GetMapping("/scrape/interrupted")
    public ResponseEntity<ApiResponse<ScrapeDtos.Interrumpida>> interrumpida() {
        var det = service.getInterrumpida();
        var b = ScrapeDtos.Interrumpida.builder().hayInterrumpida(det != null);
        if (det != null) {
            // Skipped sites are named on purpose: a site removed from the registry since the crash
            // cannot be resumed, and dropping it silently is worse.
            b.uuid(det.uuid().toString())
                    .startedAt(det.startedAt().toString())
                    .soloFaltaLaPasadaFinal(det.soloFaltaLaPasadaFinal())
                    .atendidos(det.atendidos())
                    .pendientes(det.pendientes())
                    .salteados(det.salteados());
        }
        return ResponseEntity.ok(ApiResponse.ok(b.build()));
    }

    @PostMapping("/scrape/resume")
    public ResponseEntity<ApiResponse<ScrapeDtos.Retomar>> retomar() {
        boolean ok = service.reanudar();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Retomar(ok, ok
                ? "Retomando la corrida interrumpida"
                : "No hay corrida interrumpida, o ya hay un scraping en curso")));
    }

    @PostMapping("/scrape/discard")
    public ResponseEntity<ApiResponse<ScrapeDtos.Descartar>> descartar() {
        int cerradas = service.descartarInterrumpidas();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Descartar(cerradas, cerradas > 0
                ? cerradas + " corrida(s) interrumpida(s) descartada(s). El catálogo queda como está."
                : "No había ninguna corrida interrumpida que descartar")));
    }

    @PostMapping("/scrape/cancel")
    public ResponseEntity<ApiResponse<ScrapeDtos.Cancelar>> cancelar() {
        boolean ok = service.cancelar();
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Cancelar(ok, ok
                ? "Cancelando: se deja de esperar sitios y el catálogo queda como está"
                : "No hay ningún scraping en curso")));
    }

    @PostMapping("/scrape")
    public ResponseEntity<ApiResponse<ScrapeDtos.Iniciar>> scrape(@RequestParam(required=false) Double precioMin,
            @RequestParam(required=false) Double precioMax,
            @RequestParam(required=false) Double precio,
            @RequestParam(required=false) List<String> sitios,
            @RequestParam(defaultValue="false") boolean forceRetrain) {
        if (precioMin != null) config.setPrecioMinimo(precioMin);
        if (precioMax != null) config.setPrecioMaximo(precioMax);
        if (precio    != null) config.setPrecioMaximo(precio);

        Set<String> seleccion = (sitios != null && !sitios.isEmpty())
                ? new HashSet<>(sitios) : null;

        boolean ok = service.iniciarScraping(seleccion, forceRetrain);
        return ResponseEntity.ok(ApiResponse.ok(new ScrapeDtos.Iniciar(ok,
                ok ? "Scraping iniciado" : "Ya hay un scraping en curso")));
    }
}
