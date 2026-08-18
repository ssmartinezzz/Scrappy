package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The catalog itself: the paginated product listing with server-side filters,
 * the standalone facets payload, the CSV export and the product soft-delete.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched. That matters more here
 * than anywhere else in the split — many tests call {@code controller.data(...)}
 * directly, in both the 17-arg and 21-arg overloads.</p>
 */
class CatalogoEndpoints {

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final ScraperConfig config;
    private final ar.scraper.ml.SenalEnricher senalEnricher;
    private final ar.scraper.ml.FinanciacionEnricher financiacionEnricher;

    CatalogoEndpoints(ScraperService service,
                      ar.scraper.db.DatabaseService db,
                      ScraperConfig config,
                      InflacionService inflacionService) {
        this.service = service;
        this.db = db;
        this.config = config;
        // senal y finan NO se persisten: se calculan. Antes se calculaban para el
        // catálogo entero durante la agregación; ahora, para los productos de la
        // página — menos trabajo, no más.
        this.senalEnricher = new ar.scraper.ml.SenalEnricher(db, inflacionService);
        this.financiacionEnricher = new ar.scraper.ml.FinanciacionEnricher(db, inflacionService);
    }

    private String safe(String s) { return ProductJson.safe(s); }

    private static void volcar(ObjectNode destino, java.util.Map<String, Long> conteo) {
        conteo.forEach(destino::put);
    }

    ResponseEntity<ObjectNode> data(
            int page, int size, List<String> talle, String genero, List<String> categoria,
            String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategoria,
            String fit, String estampado, String escote, String colorDominante
    ) {
        // El catálogo vive en la base, no en el snapshot de la última corrida:
        // el dashboard ya no muestra 204 sobre 13543 productos sólo porque en
        // ESTA sesión todavía nadie scrapeó (sql-catalog-filtering).
        ar.scraper.db.CatalogFilter filtro = new ar.scraper.db.CatalogFilter(
                talle, genero, categoria, q, sitio, marca, badge, segment, rubro,
                gymrat, pack, precioMin, precioMax, subCategoria,
                fit, estampado, escote, colorDominante);

        ar.scraper.db.CatalogResumen resumen = db.resumenCatalogo();
        if (resumen.total() == 0) return ResponseEntity.noContent().build();

        ar.scraper.db.CatalogPage paginaSql = db.buscarCatalogo(filtro, orden, page, size);

        // senal y finan no se persisten — se recalculan, pero SOLO para los
        // productos de esta página, no para el catálogo entero como antes.
        List<Product> pagina = financiacionEnricher.enriquecer(
                senalEnricher.enriquecer(paginaSql.productos()));

        String presetActivoLabel = db.cargarPresetActivo()
                .map(ar.scraper.db.DatabaseService.Preset::label).orElse("");

        int total = paginaSql.total();
        int totalPaginas = (int) Math.ceil((double) total / size);
        int paginaClamped = Math.max(page, 1);

        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode meta = root.putObject("meta");
        meta.put("moneda",      config.getMoneda());
        meta.put("precioMin",   config.getPrecioMinimo());
        meta.put("precioMax",   config.getPrecioMaximo());
        meta.put("rangMin",     resumen.minPrecio());
        meta.put("rangMax",     resumen.maxPrecio());
        meta.put("fecha",       fecha);
        meta.put("total",       total);
        meta.put("pagina",      paginaClamped);
        meta.put("pageSize",    size);
        meta.put("totalPaginas", totalPaginas);

        // Facets sobre el dataset COMPLETO (sin filtrar) para que no desaparezcan pills
        Facets facets = db.facetasCatalogo();
        ObjectNode facetsNode = meta.putObject("facets");
        volcar(facetsNode.putObject("talles"),          facets.talles());
        volcar(facetsNode.putObject("generos"),         facets.generos());
        volcar(facetsNode.putObject("categorias"),      facets.categorias());
        volcar(facetsNode.putObject("marcas"),          facets.marcas());
        volcar(facetsNode.putObject("badges"),          facets.badges());
        volcar(facetsNode.putObject("subCategorias"),   facets.subCategorias());
        volcar(facetsNode.putObject("fits"),            facets.fits());
        volcar(facetsNode.putObject("estampados"),      facets.estampados());
        volcar(facetsNode.putObject("escotes"),         facets.escotes());
        volcar(facetsNode.putObject("colorDominantes"), facets.colorDominantes());
        volcar(facetsNode.putObject("rubros"),          resumen.rubros());
        facetsNode.put("gymratCount", resumen.gymrat());
        facetsNode.put("packCount",   resumen.packs());

        // Conteo por sitio: del catálogo persistido. Los ERRORES no — son
        // metadata de la última corrida y no existen si todavía no hubo una.
        ObjectNode marcas = meta.putObject("marcas");
        resumen.porSitio().forEach(marcas::put);
        AggregatedResult ultimaCorrida = service.getLastResult();
        if (ultimaCorrida != null && !ultimaCorrida.erroresPorSitio().isEmpty()) {
            ObjectNode errs = meta.putObject("errores");
            ultimaCorrida.erroresPorSitio().forEach(errs::put);
        }

        // Productos de la página
        ArrayNode prods = root.putArray("productos");
        for (Product p : pagina) {
            ObjectNode n = prods.addObject();
            n.put("sitio",      safe(p.sitio()));
            n.put("nombre",     safe(p.nombre()));
            n.put("precio",     p.precio());
            n.put("precioOrig", p.precioOriginal());
            n.put("descuento",  p.tieneDescuento());
            n.put("url",        safe(p.url()));
            String img = safe(p.imagenUrl());
            if (img.startsWith("//")) img = "https:" + img;
            n.put("img",        img);
            n.put("categoria",  safe(p.categoria()));
            n.put("genero",     safe(p.genero()));
            n.put("marca",      safe(p.marca()));
            n.put("rubro",      p.rubro() != null ? p.rubro() : "indumentaria");
            n.put("gymrat",     p.gymrat());
            n.put("marcaPremium", p.marcaPremium());
            n.put("cantidadUnidades", p.cantidadUnidades());
            n.put("esPack",     p.esPack());
            n.put("precioUnitario", ProductJson.precioUnitario(p));
            n.put("sub_categoria", safe(p.subCategoria()));
            ArrayNode tallesArr = n.putArray("talles");
            if (p.talles() != null) p.talles().forEach(tallesArr::add);
            // ML score — siempre serializar para el panel de detalle
            if (p.ml() != null) {
                ObjectNode ml = n.putObject("ml");
                ml.put("badge",      p.ml().badge() != null ? p.ml().badge() : "");
                ArrayNode badgesArr = ml.putArray("badges");
                if (p.ml().badges() != null) p.ml().badges().forEach(badgesArr::add);
                ml.put("scoreP",     p.ml().scoreP());
                ml.put("ofertaReal", p.ml().ofertaReal());
                ml.put("tendencia",  p.ml().tendencia() != null ? p.ml().tendencia() : "estable");
                ml.put("pctil",      p.ml().pctilCategoria());
                ml.put("zScore",     p.ml().zScore());
                ml.put("segment",    p.ml().segment() != null ? p.ml().segment() : "standard");
            }
            // Señal de compra precomputada — siempre presente (sin_datos incluido)
            // para que el frontend decida ocultar el badge sin necesitar un fetch extra.
            Product.SenalCompra senal = p.senal() != null ? p.senal() : Product.SenalCompra.EMPTY;
            ObjectNode senalNode = n.putObject("senal");
            senalNode.put("senal",       senal.senal());
            senalNode.put("scoreCompra", senal.scoreCompra());

            // Señal de financiación precomputada — independiente de senal/scoreCompra
            // (nunca se fusionan en el mismo valor/badge). presetLabel viene del
            // preset activo, resuelto una sola vez por request (no por producto).
            Product.SenalFinanciacion finan = p.finan() != null ? p.finan() : Product.SenalFinanciacion.EMPTY;
            ObjectNode finanNode = n.putObject("senalFinanciacion");
            finanNode.put("senal",       finan.senal());
            finanNode.put("ahorroReal",  finan.ahorroReal());
            finanNode.put("vp",          finan.vp());
            finanNode.put("presetLabel", presetActivoLabel);
        }

        return ResponseEntity.ok(root);
    }

    // ---------------------------------------------------------------
    // Detalle de un producto + su historial de precios
    // ---------------------------------------------------------------

    /**
     * Un producto y su serie de precios en una sola respuesta, para la vista
     * dedicada de historial.
     *
     * <p>Se lee de la BASE, no del snapshot en memoria, por dos razones: la
     * página es deep-linkeable —se puede abrir sin haber pasado por el catálogo,
     * cuando el snapshot puede ni existir— y un producto soft-deleted tiene que
     * seguir siendo inspeccionable, que es justo cuando su historial de precios
     * es interesante.</p>
     *
     * <p>Distinto de {@code /api/historial}, que responde {@code 204} cuando no
     * hay puntos: acá un producto scrapeado una sola vez es una página que
     * igual tiene que renderizar, con sus datos y sin serie. El {@code 404}
     * queda reservado para un producto que de verdad no existe.</p>
     */
    ResponseEntity<Object> productoDetalle(String url) {
        if (url == null || url.isBlank()) return ResponseEntity.notFound().build();

        var encontrado = db.obtenerProducto(url);
        if (encontrado.isEmpty()) return ResponseEntity.notFound().build();

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode prod = root.putObject("producto");
        prod.put("url", url);
        ProductJson.escribir(prod, encontrado.get());
        root.set("historial", HistorialJson.construir(db.cargarHistorial(url)));
        return ResponseEntity.ok(root);
    }

    // ---------------------------------------------------------------
    // Facets sueltos (para cargar filtros sin productos)
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> facets() {
        ar.scraper.db.CatalogResumen resumen = db.resumenCatalogo();
        if (resumen.total() == 0) return ResponseEntity.noContent().build();

        Facets facets = db.facetasCatalogo();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        volcar(root.putObject("talles"),          facets.talles());
        volcar(root.putObject("generos"),         facets.generos());
        volcar(root.putObject("categorias"),      facets.categorias());
        volcar(root.putObject("marcas"),          facets.marcas());
        volcar(root.putObject("badges"),          facets.badges());
        volcar(root.putObject("subCategorias"),   facets.subCategorias());
        volcar(root.putObject("fits"),            facets.fits());
        volcar(root.putObject("estampados"),      facets.estampados());
        volcar(root.putObject("escotes"),         facets.escotes());
        volcar(root.putObject("colorDominantes"), facets.colorDominantes());
        // rubros NO va acá — es exclusivo de /api/data y hay un test que lo fija.
        // gymratCount/packCount sí: /api/facets publica los mismos valores.
        root.put("gymratCount", resumen.gymrat());
        root.put("packCount",   resumen.packs());
        return ResponseEntity.ok(root);
    }

    /**
     * Los tres contadores que se publican sobre el catálogo COMPLETO, sin
     * filtrar: el histograma de rubros, los gymrat y los packs.
     *
     * <p>Van sin filtrar a propósito — la UI los usa para decidir si ofrecer o
     * no una pill de filtro, así que contarlos sobre el resultado ya filtrado
     * haría desaparecer la pill apenas la usás.</p>
     *
     * <p>Eran tres barridos separados del catálogo entero, repetidos en los dos
     * endpoints y en cada request. Los facets de al lado vienen precalculados
     * del snapshot; estos no pueden, porque {@code eliminarProductoDeMemoria}
     * reusa los facets del snapshot anterior tal cual, y congelarlos ahí dejaría
     * los contadores mintiendo después de borrar un producto. Un solo recorrido
     * los deja al día sin ese riesgo.</p>
     */
    private record ContadoresGlobales(Map<String, Integer> rubros, int gymrat, int packs) {
        static ContadoresGlobales de(List<Product> productos) {
            Map<String, Integer> rubros = new LinkedHashMap<>();
            int gymrat = 0, packs = 0;
            for (Product p : productos) {
                String rubro = p.rubro();
                if (rubro != null && !rubro.isBlank()) rubros.merge(rubro.toLowerCase(), 1, Integer::sum);
                if (p.gymrat()) gymrat++;
                if (p.esPack()) packs++;
            }
            return new ContadoresGlobales(rubros, gymrat, packs);
        }
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

    ResponseEntity<ObjectNode> eliminarProducto(String url) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        db.marcarDescontinuado(url);
        service.eliminarProductoDeMemoria(url);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    // Helpers de filtrado
    // ---------------------------------------------------------------
}
