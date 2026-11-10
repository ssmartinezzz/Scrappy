package ar.scraper.web;

import ar.scraper.api.ApiResponse;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MarcasPicksController {

    private final ScraperService service;
    private final CatalogoDerivadoCache derivados;

    /**
     * Every brand with at least two products, in keyset pages. {@code sort} is {@code nombre} (default, A-Z),
     * {@code count}, {@code precio_asc} or {@code precio_desc}; anything else is a 400. {@code cursor} is the opaque
     * {@code nextCursor} of the previous page (a malformed one, or one issued under another sort, is a 400) and
     * {@code limit} defaults to 48 and is clamped to 1..100.
     */
    @GetMapping("/marcas-browser")
    public ResponseEntity<ApiResponse<MarcasPicksDtos.MarcasPagina>> marcasBrowser(@RequestParam(required = false) String rubro,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "nombre") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(derivados.marcasPagina(
                MarcasKey.de(service.snapshotVersion(), rubro, q, sort), cursor, limit == null ? 48 : limit)));
    }

    @GetMapping("/mejores")
    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(@RequestParam(required = false) String rubro,
            @RequestParam(required = false) Boolean gymrat,
            @RequestParam(required = false) String genero) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(
                derivados.mejores(MejoresKey.de(service.snapshotVersion(), rubro, gymrat, genero))));
    }

    /**
     * Every eligible product of one category, best first, in keyset pages: {@code cursor} is the opaque
     * {@code nextCursor} of the previous page, {@code limit} defaults to 24 and is clamped to 1..100. A malformed
     * cursor is a 400.
     */
    @GetMapping("/mejores/picks")
    public ResponseEntity<ApiResponse<MarcasPicksDtos.PicksPagina>> picksDeCategoria(@RequestParam String categoria,
            @RequestParam(required = false) String rubro,
            @RequestParam(required = false) Boolean gymrat,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(ApiResponse.ok(derivados.picks(
                MejoresKey.de(service.snapshotVersion(), rubro, gymrat, genero), categoria, cursor,
                limit == null ? 24 : limit)));
    }

    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(String rubro, Boolean gymrat) {
        return mejoresPorCategoria(rubro, gymrat, null);
    }

    public ResponseEntity<ApiResponse<List<MarcasPicksDtos.MejoresCategoria>>> mejoresPorCategoria(String rubro) {
        return mejoresPorCategoria(rubro, null, null);
    }
}
