package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.OpResult;
import ar.scraper.web.dto.ScrapeDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SitiosController {

    private final ScraperService service;
    private final ScraperConfig config;

    public SitiosController(ScraperService service, ScraperConfig config) {
        this.service = service;
        this.config = config;
    }

    @GetMapping("/sitios")
    public ResponseEntity<ApiResponse<ScrapeDtos.Sitios>> getSitios() {
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

    @PostMapping("/sitios")
    public ResponseEntity<ApiResponse<OpResult>> agregarSitio(@RequestBody Map<String, String> body) {
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

    @DeleteMapping("/sitios/{nombre}")
    public ResponseEntity<ApiResponse<OpResult>> eliminarSitio(@PathVariable String nombre) {
        boolean ok = service.eliminarSitio(nombre);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.of(ok,
                ok ? "Sitio eliminado" : "Sitio no encontrado")));
    }

    @PutMapping("/config")
    public ResponseEntity<ApiResponse<ScrapeDtos.ConfigResult>> updateConfig(@RequestBody Map<String, Object> body) {
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
