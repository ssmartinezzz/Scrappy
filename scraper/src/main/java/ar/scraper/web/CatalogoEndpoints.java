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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/** The catalog listing, facets, CSV export and product soft-delete. Mappings live in {@link ApiController}. */
class CatalogoEndpoints {

    private final ScraperService service;
    private final CatalogQueryPort catalogQuery;
    private final ProductPort productos;
    private final ar.scraper.financiacion.PresetPort presets;
    private final ar.scraper.catalog.HistorialPort historial;
    private final ScraperConfig config;
    private final ar.scraper.ml.SenalEnricher senalEnricher;
    private final ar.scraper.ml.FinanciacionEnricher financiacionEnricher;

    CatalogoEndpoints(ScraperService service,
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

    ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(
            int page, int size, List<String> talle, String genero, List<String> categoria,
            String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategoria,
            String fit, String estampado, String escote, String colorDominante
    ) {
        CatalogFilter filtro = new CatalogFilter(
                talle, genero, categoria, q, sitio, marca, badge, segment, rubro,
                gymrat, pack, precioMin, precioMax, subCategoria,
                fit, estampado, escote, colorDominante);

        // Summary and page share one bound: an unbounded summary next to a bounded page
        // would offer facets the page cannot satisfy.
        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();

        CatalogResumen resumen = catalogQuery.resumen(cota);
        // The 1-based port below adds one: keep Integer.MAX_VALUE from wrapping to a negative page.
        int numero = Math.min(Math.max(page, 0), Integer.MAX_VALUE - 1);
        if (resumen.total() == 0) {
            var vacio = new CatalogoDtos.Meta(
                    config.getMoneda(), config.getPrecioMinimo(), config.getPrecioMaximo(),
                    0, 0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    CatalogoDtos.FacetsDto.vacio(), Map.of(), null);
            return ResponseEntity.ok(new ApiResponse<>(new CatalogoDtos.Catalogo(vacio, List.of()),
                    PageMeta.of(numero, size, 0)));
        }

        // The port is 1-based; the API is 0-based.
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

    // ---------------------------------------------------------------
    // Detalle de un producto + su historial de precios
    // ---------------------------------------------------------------

    /**
     * Product plus price series for the history view. Reads the DB, not the in-memory snapshot:
     * the page is deep-linkable and a soft-deleted product must stay inspectable. Unlike
     * {@code /api/historial} a product with no points is still a 200; 404 means it does not exist.
     */
    ResponseEntity<ApiResponse<CatalogoDtos.ProductoDetalle>> productoDetalle(String key) {
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

    // ---------------------------------------------------------------
    // Facets sueltos (para cargar filtros sin productos)
    // ---------------------------------------------------------------
    ResponseEntity<ApiResponse<CatalogoDtos.FacetsDto>> facets() {
        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();

        CatalogResumen resumen = catalogQuery.resumen(cota);
        if (resumen.total() == 0) return ResponseEntity.ok(ApiResponse.ok(CatalogoDtos.FacetsDto.vacio()));

        // rubros is exclusive to /api/data; a test pins that.
        return ResponseEntity.ok(ApiResponse.ok(CatalogoDtos.FacetsDto.of(
                catalogQuery.facetas(cota), null, resumen.gymrat(), resumen.packs())));
    }

    // ---------------------------------------------------------------
    // CSV — descarga todo sin filtrar
    // ---------------------------------------------------------------
    ResponseEntity<String> csv() throws Exception {
        String content = service.generarCsv();
        if (content.isBlank()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ofertas.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body("\uFEFF" + content);
    }

    ResponseEntity<ApiResponse<OpResult>> eliminarProducto(String url) {
        productos.marcarDescontinuado(url);
        service.eliminarProductoDeMemoria(url);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
