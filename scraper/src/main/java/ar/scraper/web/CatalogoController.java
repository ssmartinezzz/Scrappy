package ar.scraper.web;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.Facets;
import ar.scraper.json.HistorialJson;
import ar.scraper.json.ProductJson;
import ar.scraper.catalog.ProductPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.api.PageMeta;
import ar.scraper.web.dto.CatalogoDtos;
import ar.scraper.web.dto.OpResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

@RestController
@RequestMapping("/api")
public class CatalogoController {

    private final ScraperService service;
    private final CatalogQueryPort catalogQuery;
    private final ProductPort productos;
    private final ar.scraper.financiacion.PresetPort presets;
    private final ar.scraper.catalog.HistorialPort historial;
    private final ScraperConfig config;
    private final ar.scraper.ml.SenalEnricher senalEnricher;
    private final ar.scraper.ml.FinanciacionEnricher financiacionEnricher;

    public CatalogoController(ScraperService service,
                      ar.scraper.financiacion.PresetPort presets,
                      ar.scraper.catalog.HistorialPort historial,
                      CatalogQueryPort catalogQuery,
                      ProductPort productos,
                      ScraperConfig config,
                      IndiceService indiceService) {
        this.service = service;
        this.catalogQuery = catalogQuery;
        this.productos = productos;
        this.presets = presets;
        this.historial = historial;
        this.config = config;
        this.senalEnricher = new ar.scraper.ml.SenalEnricher(historial, indiceService);
        this.financiacionEnricher = new ar.scraper.ml.FinanciacionEnricher(presets, indiceService);
    }

    @GetMapping("/data")
    public ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(@RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "24")  int size,
            @RequestParam(required = false)     List<String> talle,
            @RequestParam(required = false)     String genero,
            @RequestParam(required = false)     List<String> categoria,
            @RequestParam(required = false)     String q,
            @RequestParam(required = false)     String sitio,
            @RequestParam(required = false)     List<String> marca,
            @RequestParam(required = false)     String badge,
            @RequestParam(required = false)     String segment,
            @RequestParam(required = false)     String rubro,
            @RequestParam(required = false)     Boolean gymrat,
            @RequestParam(defaultValue = "precio_asc") String orden,
            @RequestParam(required = false)     Boolean pack,
            @RequestParam(required = false)     Double precioMin,
            @RequestParam(required = false)     Double precioMax,
            @RequestParam(required = false)     List<String> subCategoria,
            @RequestParam(required = false)     String fit,
            @RequestParam(required = false)     String estampado,
            @RequestParam(required = false)     String escote,
            @RequestParam(required = false)     String colorDominante) {
        CatalogFilter filtro = new CatalogFilter(
                talle, genero, categoria, q, sitio, marca, badge, segment, rubro,
                gymrat, pack, precioMin, precioMax, subCategoria,
                fit, estampado, escote, colorDominante);

        // Summary and page share one bound: an unbounded summary next to a bounded page would offer
        // facets the page cannot satisfy.
        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();

        CatalogResumen resumen = catalogQuery.resumen(cota);
        int numero = Math.min(Math.max(page, 0), Integer.MAX_VALUE - 1);
        if (resumen.total() == 0) {
            var vacio = new CatalogoDtos.Meta(
                    config.getMoneda(), config.getPrecioMinimo(), config.getPrecioMaximo(),
                    0, 0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    CatalogoDtos.FacetsDto.vacio(), Map.of(), null);
            return ResponseEntity.ok(new ApiResponse<>(new CatalogoDtos.Catalogo(vacio, List.of()),
                    PageMeta.of(numero, size, 0)));
        }

        CatalogPage paginaSql = catalogQuery.buscar(filtro, orden, numero + 1, size, cota);

        // senal/finan are computed, not persisted: only for this page's products.
        List<Product> pagina = financiacionEnricher.enriquecer(
                senalEnricher.enriquecer(paginaSql.productos()));

        String presetActivoLabel = presets.cargarPresetActivo()
                .map(ar.scraper.financiacion.Preset::label).orElse("");

        // Facets over the FULL dataset so filter pills do not vanish once used.
        Facets facets = catalogQuery.facetas(cota);
        AggregatedResult ultimaCorrida = service.getLastResult();
        // Errors are last-run metadata; they do not exist before the first run.
        Map<String, String> errores = ultimaCorrida != null && !ultimaCorrida.erroresPorSitio().isEmpty()
                ? ultimaCorrida.erroresPorSitio() : null;

        var meta = new CatalogoDtos.Meta(
                config.getMoneda(), config.getPrecioMinimo(), config.getPrecioMaximo(),
                resumen.minPrecio(), resumen.maxPrecio(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                CatalogoDtos.FacetsDto.of(facets, resumen.rubros(), resumen.gymrat(), resumen.packs()),
                resumen.porSitio(), errores);
        List<CatalogoDtos.ProductoRow> filas = pagina.stream()
                .map(p -> CatalogoDtos.ProductoRow.of(p, presetActivoLabel)).toList();

        return ResponseEntity.ok(new ApiResponse<>(new CatalogoDtos.Catalogo(meta, filas),
                PageMeta.of(numero, size, paginaSql.total())));
    }

    /**
     * Reads the DB, not the in-memory snapshot: the page is deep-linkable and a soft-deleted
     * product must stay inspectable. Unlike {@code /api/historial} a product with no points is
     * still a 200; 404 means it does not exist.
     */
    @GetMapping("/producto/{key}")
    public ResponseEntity<ApiResponse<CatalogoDtos.ProductoDetalle>> productoDetalle(@PathVariable String key) {
        var encontrado = StringUtils.isBlank(key) ? java.util.Optional.<Product>empty()
                : productos.obtenerProductoPorKey(key);
        if (encontrado.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "El producto no existe.");
        }

        String url = encontrado.get().url();
        ObjectNode prod = JsonNodeFactory.instance.objectNode();
        prod.put("url", url);
        ProductJson.escribir(prod, encontrado.get());
        return ResponseEntity.ok(ApiResponse.ok(new CatalogoDtos.ProductoDetalle(
                prod, HistorialJson.construir(historial.cargarHistorial(url)))));
    }

    // --------------------------------------------------------------- Facets sueltos (para cargar
    // filtros sin productos) ---------------------------------------------------------------
    @GetMapping("/facets")
    public ResponseEntity<ApiResponse<CatalogoDtos.FacetsDto>> facets() {
        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();

        CatalogResumen resumen = catalogQuery.resumen(cota);
        if (resumen.total() == 0) return ResponseEntity.ok(ApiResponse.ok(CatalogoDtos.FacetsDto.vacio()));

        return ResponseEntity.ok(ApiResponse.ok(CatalogoDtos.FacetsDto.of(
                catalogQuery.facetas(cota), null, resumen.gymrat(), resumen.packs())));
    }

    // --------------------------------------------------------------- CSV — descarga todo sin
    // filtrar ---------------------------------------------------------------
    @GetMapping("/csv")
    public ResponseEntity<String> csv() throws Exception {
        String content = service.generarCsv();
        if (content.isBlank()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ofertas.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body("\uFEFF" + content);
    }

    @DeleteMapping("/data")
    public ResponseEntity<ApiResponse<OpResult>> eliminarProducto(@RequestParam String url) {
        productos.marcarDescontinuado(url);
        service.eliminarProductoDeMemoria(url);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
