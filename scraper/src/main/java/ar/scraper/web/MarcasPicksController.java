package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Brand browser and the curated "Mejores picks" per category. */
@RestController
@RequestMapping("/api")
public class MarcasPicksController {

    private final ScraperService service;
    private final CatalogoDerivadoCache derivados;

    public MarcasPicksController(ScraperService service, CatalogoDerivadoCache derivados) {
        this.service = service;
        this.derivados = derivados;
    }

    @GetMapping("/marcas-browser")
    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.Marca>>> marcasBrowser(@RequestParam(required = false) String rubro,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "count") String sort) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(
                derivados.marcas(MarcasKey.de(service.snapshotVersion(), rubro, q, sort))));
    }

    @GetMapping("/mejores")
    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(@RequestParam(required = false) String rubro) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(
                derivados.mejores(MejoresKey.de(service.snapshotVersion(), rubro))));
    }
}
