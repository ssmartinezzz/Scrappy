package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.catalog.ProductKey;
import ar.scraper.catalog.ProductPort;
import ar.scraper.json.HistorialJson;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import ar.scraper.web.dto.ComparadorDtos;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The same article in the other stores, for the multi-line price-history chart. "Same article" is
 * the cached multi-site grouping, so a product outside the in-memory snapshot has no siblings.
 */
@RestController
@RequestMapping("/api")
public class ProductoComparadoController {

    private final ScraperService service;
    private final CatalogoDerivadoCache derivados;
    private final ProductPort productos;
    private final HistorialPort historial;
    private final PoliticaStock politicaStock;

    public ProductoComparadoController(ScraperService service, CatalogoDerivadoCache derivados,
                                       ProductPort productos, HistorialPort historial, PoliticaStock politicaStock) {
        this.service = service;
        this.derivados = derivados;
        this.productos = productos;
        this.historial = historial;
        this.politicaStock = politicaStock;
    }

    @GetMapping("/producto/{key}/comparado")
    public ResponseEntity<ApiResponse<List<ComparadorDtos.Hermano>>> comparado(@PathVariable String key) {
        var encontrado = StringUtils.isBlank(key) ? java.util.Optional.<Product>empty()
                : productos.obtenerProductoPorKey(key);
        if (encontrado.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "El producto no existe.");
        }
        String url = encontrado.get().url();

        List<Product> hermanos = derivados.grupos(GruposKey.de(service.snapshotVersion(), null, null, null, true))
                .stream()
                .filter(g -> g.getProductos().stream().anyMatch(p -> url.equals(p.url())))
                .flatMap(g -> g.getProductos().stream())
                .filter(p -> !url.equals(p.url()))
                .toList();
        if (hermanos.isEmpty()) return ResponseEntity.ok(ApiResponse.ok(List.of()));

        Map<String, List<HistorialEntry>> series = historial.getHistorialPrecios(hermanos.stream().map(Product::url).toList());
        return ResponseEntity.ok(ApiResponse.ok(hermanos.stream().map(p -> new ComparadorDtos.Hermano(
                p.sitio(), p.url(), ProductKey.of(p.url()), p.precio(), p.imagenUrl(),
                politicaStock.estado(p).wire(),
                HistorialJson.construir(series.getOrDefault(p.url(), List.of()).stream()
                        .map(e -> Map.<String, Object>of("fecha", e.fecha(), "precio", e.precio())).toList()))).toList()));
    }
}
