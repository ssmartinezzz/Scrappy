package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.springframework.http.ResponseEntity;

import java.util.List;

/** Brand browser and the curated "Mejores picks" per category. Mappings live in {@link ApiController}. */
class MarcasPicksEndpoints {

    private final ScraperService service;
    private final CatalogoDerivadoCache derivados;

    MarcasPicksEndpoints(ScraperService service, CatalogoDerivadoCache derivados) {
        this.service = service;
        this.derivados = derivados;
    }

    ResponseEntity<ApiResponse<List<MarcasPicksDtos.Marca>>> marcasBrowser(String rubro, String q, String sort) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(
                derivados.marcas(MarcasKey.de(service.snapshotVersion(), rubro, q, sort))));
    }

    ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(String rubro) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(
                derivados.mejores(MejoresKey.de(service.snapshotVersion(), rubro))));
    }
}
