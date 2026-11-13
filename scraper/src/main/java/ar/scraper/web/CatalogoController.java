package ar.scraper.web;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.normalize.SportTaxonomy;
import ar.scraper.catalog.CatalogCursor;
import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogKeysetPage;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.PoliticaStock;
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
import ar.scraper.web.dto.PromoDtos;
import ar.scraper.catalog.ProductoRef;
import ar.scraper.model.promo.PromoVigente;
import ar.scraper.promos.ResumenDePromos;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
    private final PoliticaStock politicaStock;
    private final ar.scraper.catalog.PromoQueryPort promos;

    /** Default stock policy (7 days, system clock): for callers that build the controller by hand. */
    public CatalogoController(ScraperService service,
                      ar.scraper.financiacion.PresetPort presets,
                      ar.scraper.catalog.HistorialPort historial,
                      CatalogQueryPort catalogQuery,
                      ProductPort productos,
                      ScraperConfig config,
                      IndiceService indiceService) {
        this(service, presets, historial, catalogQuery, productos, config, indiceService, PoliticaStock.porDefecto());
    }

    /** No promos: for callers that build the controller by hand and do not care about them. */
    public CatalogoController(ScraperService service,
                      ar.scraper.financiacion.PresetPort presets,
                      ar.scraper.catalog.HistorialPort historial,
                      CatalogQueryPort catalogQuery,
                      ProductPort productos,
                      ScraperConfig config,
                      IndiceService indiceService,
                      PoliticaStock politicaStock) {
        this(service, presets, historial, catalogQuery, productos, config, indiceService, politicaStock,
                ar.scraper.catalog.PromoQueryPort.VACIO);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CatalogoController(ScraperService service,
                      ar.scraper.financiacion.PresetPort presets,
                      ar.scraper.catalog.HistorialPort historial,
                      CatalogQueryPort catalogQuery,
                      ProductPort productos,
                      ScraperConfig config,
                      IndiceService indiceService,
                      PoliticaStock politicaStock,
                      ar.scraper.catalog.PromoQueryPort promos) {
        this.politicaStock = politicaStock;
        this.promos = promos;
        this.service = service;
        this.catalogQuery = catalogQuery;
        this.productos = productos;
        this.presets = presets;
        this.historial = historial;
        this.config = config;
        this.senalEnricher = new ar.scraper.ml.SenalEnricher(historial, indiceService);
        this.financiacionEnricher = new ar.scraper.ml.FinanciacionEnricher(presets, indiceService);
    }

    /** A sport code from the {@code deporte} domain, lower-cased; blank is no filter, a typo is a 400. */
    private static String deporteConocido(String valor) {
        if (StringUtils.isBlank(valor)) return null;
        String codigo = valor.trim().toLowerCase(Locale.ROOT);
        if (!SportTaxonomy.KEYWORDS.containsKey(codigo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "deporte desconocido: " + codigo + ". Válidos: " + String.join(", ", SportTaxonomy.KEYWORDS.keySet()));
        }
        return codigo;
    }

    /** {@code stock}: absent/blank/{@code todos} = flag only, {@code excluir_sin_stock} = drop confirmed sold-out rows. */
    private CatalogFilter conStock(CatalogFilter filtro, String stock) {
        if (StringUtils.isBlank(stock)) return filtro;
        return switch (stock.trim().toLowerCase(Locale.ROOT)) {
            case "todos" -> filtro;
            case "excluir_sin_stock" -> filtro.conExcluirSinStock(politicaStock.corte());
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "stock desconocido: " + stock.trim() + ". Válidos: todos, excluir_sin_stock");
        };
    }

    /** Page-number mode: the same read as {@link #data} with no cursor. */
    public ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(int page, int size, List<String> talle, String genero,
            List<String> categoria, String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack, Double precioMin, Double precioMax,
            List<String> subCategoria, String fit, String estampado, String escote, String colorDominante,
            List<String> excluirCategoria, String deporte) {
        return data(page, size, talle, genero, categoria, q, sitio, marca, badge, segment, rubro, gymrat, orden, pack,
                precioMin, precioMax, subCategoria, fit, estampado, escote, colorDominante, excluirCategoria, deporte, null, null);
    }

    /** Keyset-aware read with no stock filter. */
    public ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> data(int page, int size, List<String> talle, String genero,
            List<String> categoria, String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack, Double precioMin, Double precioMax,
            List<String> subCategoria, String fit, String estampado, String escote, String colorDominante,
            List<String> excluirCategoria, String deporte, String cursor) {
        return data(page, size, talle, genero, categoria, q, sitio, marca, badge, segment, rubro, gymrat, orden, pack,
                precioMin, precioMax, subCategoria, fit, estampado, escote, colorDominante, excluirCategoria, deporte,
                cursor, null);
    }

    /**
     * {@code cursor} switches to keyset mode (see {@link #dataPorCursor}); absent, {@code page} + {@code size} paginate
     * with OFFSET as always.
     */
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
            @RequestParam(required = false)     String colorDominante,
            @RequestParam(required = false)     List<String> excluirCategoria,
            @RequestParam(required = false)     String deporte,
            @RequestParam(required = false)     String cursor,
            @RequestParam(required = false)     String stock) {
        CatalogFilter filtro = new CatalogFilter(
                talle, genero, categoria, q, sitio, marca, badge, segment, rubro,
                gymrat, pack, precioMin, precioMax, subCategoria,
                fit, estampado, escote, colorDominante, excluirCategoria, deporteConocido(deporte));
        filtro = conStock(filtro, stock);
        if (cursor != null) return dataPorCursor(filtro, size, cursor, orden);

        // Summary and page share one bound: an unbounded summary next to a bounded page would offer
        // facets the page cannot satisfy.
        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();

        CatalogResumen resumen = catalogQuery.resumen(cota);
        int numero = Math.min(Math.max(page, 0), Integer.MAX_VALUE - 1);
        if (resumen.total() == 0) return vacia(PageMeta.of(numero, size, 0));

        CatalogPage paginaSql = catalogQuery.buscar(filtro, orden, numero + 1, size, cota);

        // Price orders can continue by cursor: hand out the first cursor so a client switches from page numbers to
        // keyset without a separate "first page" call. Other orders have no keyset, so no cursor.
        String siguiente = null;
        List<Product> filasSql = paginaSql.productos();
        if (CatalogCursor.admite(orden) && !filasSql.isEmpty()
                && (long) numero * size + filasSql.size() < paginaSql.total()) {
            Product ultimo = filasSql.get(filasSql.size() - 1);
            siguiente = new CatalogCursor(ultimo.precio(), ultimo.url()).codificar();
        }
        return responder(resumen, cota, filasSql, PageMeta.of(numero, size, paginaSql.total()), siguiente);
    }

    /**
     * Keyset mode of {@code /api/data}, selected by the presence of {@code cursor} (empty = from the start). Only
     * {@code precio_asc} and {@code precio_desc} have a keyset; any other order, or a cursor that is not one
     * {@code nextCursor} produced, is a 400. No OFFSET and no COUNT: {@code page.total} is -1.
     */
    private ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> dataPorCursor(CatalogFilter filtro, int size,
            String cursor, String orden) {
        if (!CatalogCursor.admite(orden)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida",
                    "cursor solo admite orden=precio_asc o precio_desc, no " + orden);
        }
        CatalogCursor desde;
        try {
            desde = StringUtils.isBlank(cursor) ? null : CatalogCursor.decodificar(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "cursor inválido");
        }

        java.util.Optional<java.time.Instant> cota = service.cotaDeLectura();
        CatalogResumen resumen = catalogQuery.resumen(cota);
        PageMeta meta = new PageMeta(0, size, -1, 0);
        if (resumen.total() == 0) return vacia(meta);

        CatalogKeysetPage pagina = catalogQuery.buscarPorCursor(filtro, orden, desde, size, cota);
        return responder(resumen, cota, pagina.productos(), meta,
                pagina.siguiente() == null ? null : pagina.siguiente().codificar());
    }

    private ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> vacia(PageMeta meta) {
        var vacio = new CatalogoDtos.Meta(
                config.getMoneda(), config.getPrecioMinimo(), config.getPrecioMaximo(),
                0, 0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                CatalogoDtos.FacetsDto.vacio(), Map.of(), null);
        return ResponseEntity.ok(new ApiResponse<>(new CatalogoDtos.Catalogo(vacio, List.of()), meta));
    }

    private ResponseEntity<ApiResponse<CatalogoDtos.Catalogo>> responder(CatalogResumen resumen,
            java.util.Optional<java.time.Instant> cota, List<Product> filasSql, PageMeta page, String siguiente) {
        // senal/finan are computed, not persisted: only for this page's products.
        List<Product> pagina = financiacionEnricher.enriquecer(senalEnricher.enriquecer(filasSql));

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
        // One set-based read for the whole page, whatever its size.
        Map<String, List<PromoVigente>> promosPorUrl = promos.paraProductos(
                pagina.stream().map(p -> new ProductoRef(p.url(), p.sitio())).toList());
        List<CatalogoDtos.ProductoRow> filas = pagina.stream()
                .map(p -> CatalogoDtos.ProductoRow.of(p, presetActivoLabel, politicaStock,
                        promosPorUrl.getOrDefault(p.url(), List.of()))).toList();

        return ResponseEntity.ok(new ApiResponse<>(new CatalogoDtos.Catalogo(meta, filas, siguiente), page));
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
        ProductJson.escribir(prod, encontrado.get(), politicaStock);
        List<PromoDtos.Promo> promosDelProducto = ResumenDePromos.ordenar(
                promos.paraProductos(List.of(new ProductoRef(url, encontrado.get().sitio()))).getOrDefault(url, List.of()))
                .stream().map(PromoDtos.Promo::of).toList();
        return ResponseEntity.ok(ApiResponse.ok(new CatalogoDtos.ProductoDetalle(
                prod, HistorialJson.construir(historial.cargarHistorial(url)), promosDelProducto)));
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
