package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.identity.ActorResolver;
import ar.scraper.agent.AgentConfig;
import ar.scraper.agent.CatalogAgentService;
import ar.scraper.agent.ReclassifyProposal;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {


    // ─── Cache para endpoints costosos ───────────────────────────────────────────
    private final java.util.concurrent.ConcurrentHashMap<String, Object> endpointCache
        = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastScrapeTs = 0;

    private <T> T cached(String key, java.util.function.Supplier<T> fn) {
        long now = System.currentTimeMillis();
        // Invalidar si pasaron más de 3 minutos o hubo un nuevo scraping
        long scrapeTs = service.getLastResult() != null
            ? service.getLastResult().hashCode() : 0;
        String fullKey = key + "|" + scrapeTs;
        if (!endpointCache.containsKey(fullKey)) {
            endpointCache.clear(); // limpiar entradas viejas
            endpointCache.put(fullKey, fn.get());
        }
        @SuppressWarnings("unchecked") T val = (T) endpointCache.get(fullKey);
        return val;
    }

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(ApiController.class);

    private final ScraperService   service;
    private final InflacionService inflacionService;
    private final ScraperConfig    config;

    private final ar.scraper.aggregator.ResultAggregator aggregator;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.aggregator.grouping.GroupingService grouping;
    private final ar.scraper.ml.PythonRunner pythonRunner;
    private final OutfitService outfitService;
    private final RecommendationService recommendationService;
    private final CatalogAgentService catalogAgentService;
    private final AgentConfig agentConfig;
    private final ActorResolver actorResolver;

    /**
     * LLM Catalog Agent endpoints, extracted to their own class (backlog A3).
     * The request mappings stay on this controller and delegate here, so routes
     * and callers are unchanged.
     */
    private final AgentEndpoints agentEndpoints;

    /**
     * Financing presets, buy recommendation and inflation endpoints, extracted
     * to their own class (backlog A3) — same delegation shape as
     * {@link #agentEndpoints}.
     */
    private final FinanciacionEndpoints financiacionEndpoints;

    /**
     * Outfit / supplement builder and saved-outfit endpoints, extracted to their
     * own class (backlog A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final OutfitsEndpoints outfitsEndpoints;

    /**
     * "Para ti" feed endpoints, extracted to their own class (backlog A3) —
     * same delegation shape as {@link #agentEndpoints}.
     */
    private final RecomendadosEndpoints recomendadosEndpoints;

    /**
     * Favoritos endpoints, extracted to their own class (backlog A3) — same
     * delegation shape as {@link #agentEndpoints}.
     */
    private final FavoritosEndpoints favoritosEndpoints;

    /**
     * ML pipeline / training endpoints, extracted to their own class (backlog
     * A3) — same delegation shape as {@link #agentEndpoints}.
     */
    private final MlEndpoints mlEndpoints;

    /**
     * Primary constructor (manual-classification-lock Phase 7) — adds the
     * {@link ActorResolver} seam (architecture/session-readiness, obs #773):
     * {@code agentApply} resolves the acting identity through this ONE seam,
     * never inline. Spring wires this one (see {@code @Autowired} below); two
     * legacy overloads are kept right below purely so the existing unit tests
     * that construct {@code ApiController} directly keep compiling unchanged.
     */
    @Autowired
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig,
                         ActorResolver actorResolver) {
        this.service           = service;
        this.inflacionService  = inflacionService;
        this.config            = config;
        this.aggregator        = aggregator;
        this.db                = db;
        this.grouping          = grouping;
        this.pythonRunner      = pythonRunner;
        this.outfitService     = outfitService;
        this.recommendationService = recommendationService;
        this.catalogAgentService = catalogAgentService;
        this.agentConfig        = agentConfig;
        this.actorResolver      = actorResolver;
        this.agentEndpoints     = new AgentEndpoints(service, db, catalogAgentService,
                                                     agentConfig, actorResolver);
        this.financiacionEndpoints = new FinanciacionEndpoints(service, inflacionService,
                                                               db, aggregator);
        this.outfitsEndpoints   = new OutfitsEndpoints(service, db, outfitService);
        this.recomendadosEndpoints = new RecomendadosEndpoints(service, db, recommendationService);
        this.favoritosEndpoints = new FavoritosEndpoints(service, db);
        this.mlEndpoints        = new MlEndpoints(service, db, aggregator, pythonRunner);
    }

    /**
     * Legacy 11-arg constructor (pre manual-classification-lock, llm-catalog-nlp
     * shape) — see the note on the primary constructor above. Defaults to a real
     * (not fake) {@link ActorResolver} — it has no dependencies of its own, so
     * this is behaviorally identical to Spring injecting it.
     */
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService,
                         CatalogAgentService catalogAgentService,
                         AgentConfig agentConfig) {
        this(service, inflacionService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, catalogAgentService, agentConfig, new ActorResolver());
    }

    /** Legacy 9-arg constructor (pre-agent) — see the note on the primary constructor above. */
    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.grouping.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService,
                         RecommendationService recommendationService) {
        this(service, inflacionService, config, aggregator, db, grouping, pythonRunner,
             outfitService, recommendationService, null, null);
    }

    // ---------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------
    @GetMapping("/status")
    public ResponseEntity<ObjectNode> status() {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        b.put("status",    service.getStatus().name());
        b.put("mensaje",   service.getStatusMsg());
        var lr = service.getLastResult();
        b.put("tieneData", lr != null);
        if (lr != null) {
            b.put("total", lr.productos().size());
            // Stats ML del último scraping
            b.put("mlRefinadas", service.getUltimasCategoriasRefinadas());
            b.put("mlModeloActivo", new java.io.File("_models/text_classifier.pkl").exists());
            // Extraction quality stats — additive, does not change existing keys
            var st = lr.statsPorSitio();
            if (st != null && !st.isEmpty()) {
                ObjectNode sNode = b.putObject("extractionStats");
                st.forEach((sitio, s) -> {
                    ObjectNode sn = sNode.putObject(sitio);
                    sn.put("total",  s.total());
                    sn.put("valid",  s.valid());
                    sn.put("misses", s.misses());
                });
            }
        }

        // Progreso en tiempo real
        ScraperService.ProgressData pd = service.getProgressData();
        if (pd != null) {
            ObjectNode prog = b.putObject("progreso");
            prog.put("total",       pd.total());
            prog.put("completados", pd.completados());
            prog.put("productos",   pd.productosAcumulados());
            ArrayNode sitiosArr = prog.putArray("sitios");
            for (var sp : pd.sitios()) {
                ObjectNode sn = sitiosArr.addObject();
                sn.put("nombre",  sp.nombre());
                sn.put("estado",  sp.estado().name().toLowerCase());
                sn.put("count",   sp.productos());
                sn.put("durMs",   sp.duracionMs());
                if (sp.error() != null && !sp.error().isBlank())
                    sn.put("error", sp.error().length() > 60
                            ? sp.error().substring(0, 60) + "..." : sp.error());
            }
        }
        return ResponseEntity.ok(b);
    }

    // ---------------------------------------------------------------
    // Lanzar scraping
    // ---------------------------------------------------------------
    @PostMapping("/scrape")
    public ResponseEntity<ObjectNode> scrape(
            @RequestParam(required=false) Double precioMin,
            @RequestParam(required=false) Double precioMax,
            @RequestParam(required=false) Double precio,          // legado
            @RequestParam(required=false) List<String> sitios,   // seleccion opcional
            @RequestParam(defaultValue="false") boolean forceRetrain) {
        ObjectNode b = JsonNodeFactory.instance.objectNode();
        if (precioMin != null) config.setPrecioMinimo(precioMin);
        if (precioMax != null) config.setPrecioMaximo(precioMax);
        if (precio    != null) config.setPrecioMaximo(precio);

        Set<String> seleccion = (sitios != null && !sitios.isEmpty())
                ? new HashSet<>(sitios) : null;

        boolean ok = service.iniciarScraping(seleccion, forceRetrain);
        b.put("iniciado", ok);
        b.put("mensaje", ok ? "Scraping iniciado" : "Ya hay un scraping en curso");
        return ResponseEntity.ok(b);
    }

    @DeleteMapping("/db/productos")
    public ResponseEntity<String> limpiarProductos() {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
        }
        try {
            db.limpiarProductos();
            service.clearLastResult();
            aggregator.clearMlOutput();
            return ResponseEntity.ok("Catálogo eliminado.");
        } catch (Exception e) {
            LOG.error("[API] Error al limpiar productos", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/db/ml")
    public ResponseEntity<String> limpiarMl() {
        if (service.getStatus() == ScraperService.ScraperStatus.RUNNING) {
            return ResponseEntity.status(409).body("Hay un scraping en curso. Esperá a que termine.");
        }
        try {
            db.limpiarMlOutput();
            aggregator.clearMlOutput();
            return ResponseEntity.ok("Datos ML eliminados.");
        } catch (Exception e) {
            LOG.error("[API] Error al limpiar ML", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Datos con filtros + paginación server-side
    //
    // Query params:
    //   page        int (default 1)
    //   size        int (default 24)
    //   talle       string[] (multi, OR dentro del grupo)
    //   genero      string   (single)
    //   categoria   string[] (multi, OR)
    //   q           string   (búsqueda full-text en nombre)
    //   orden       precio_asc | precio_desc | nombre (default precio_asc)
    // ---------------------------------------------------------------
    /**
     * Legacy 17-arg overload (pre-PR6) — retained for backward source
     * compatibility with existing test call sites built before the 4 additive
     * visual-attribute filters (T6.7/T6.8) were added. Defaults
     * fit/estampado/escote/colorDominante to {@code null} (no filter).
     */
    public ResponseEntity<ObjectNode> data(
            int page, int size, List<String> talle, String genero, List<String> categoria,
            String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategoria
    ) {
        return data(page, size, talle, genero, categoria, q, sitio, marca, badge, segment,
                rubro, gymrat, orden, pack, precioMin, precioMax, subCategoria,
                null, null, null, null);
    }

    @GetMapping("/data")
    public ResponseEntity<ObjectNode> data(
            @RequestParam(defaultValue = "1")   int page,
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
            @RequestParam(required = false)     String colorDominante
    ) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        // Preset activo de financiación — resuelto UNA sola vez por request,
        // no por producto (evita N+1 lecturas a la DB sobre todo el catálogo).
        String presetActivoLabel = db.cargarPresetActivo()
                .map(ar.scraper.db.DatabaseService.Preset::label).orElse("");

        // 1. Aplicar filtros
        List<Product> filtrados = aplicarFiltros(r.productos(), talle, genero, categoria, q, sitio, marca, badge, segment, rubro, gymrat, pack, precioMin, precioMax, subCategoria, fit, estampado, escote, colorDominante);

        // 2. Ordenar
        filtrados = ordenar(filtrados, orden);

        // 3. Paginar
        int total        = filtrados.size();
        int totalPaginas = (int) Math.ceil((double) total / size);
        int paginaClamped = Math.max(page, 1);
        long desdeCandidato = (long) (paginaClamped - 1) * size;
        int desde        = (int) Math.min(desdeCandidato, total);
        int hasta        = Math.min(desde + size, total);
        List<Product> pagina = filtrados.subList(desde, hasta);

        // 4. Construir respuesta
        String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode meta = root.putObject("meta");
        meta.put("moneda",      config.getMoneda());
        meta.put("precioMin",   config.getPrecioMinimo());
        meta.put("precioMax",   config.getPrecioMaximo());
        meta.put("rangMin",     r.minPrecio());
        meta.put("rangMax",     r.maxPrecio());
        meta.put("fecha",       fecha);
        meta.put("total",       total);
        meta.put("pagina",      paginaClamped);
        meta.put("pageSize",    size);
        meta.put("totalPaginas", totalPaginas);

        // Facets sobre el dataset COMPLETO (sin filtrar) para que no desaparezcan pills
        Facets facets = r.facets();
        ObjectNode facetsNode = meta.putObject("facets");
        ObjectNode tallesNode = facetsNode.putObject("talles");
        facets.talles().forEach(tallesNode::put);
        ObjectNode generosNode = facetsNode.putObject("generos");
        facets.generos().forEach(generosNode::put);
        ObjectNode catsNode = facetsNode.putObject("categorias");
        facets.categorias().forEach(catsNode::put);
        ObjectNode marcasNode = facetsNode.putObject("marcas");
        facets.marcas().forEach(marcasNode::put);
        ObjectNode badgesNode = facetsNode.putObject("badges");
        facets.badges().forEach(badgesNode::put);
        ObjectNode subCategoriasNode = facetsNode.putObject("subCategorias");
        facets.subCategorias().forEach(subCategoriasNode::put);
        // Facets de atributos visuales (fashion-image-classification PR6, T6.6)
        ObjectNode fitsNode = facetsNode.putObject("fits");
        facets.fits().forEach(fitsNode::put);
        ObjectNode estampadosNode = facetsNode.putObject("estampados");
        facets.estampados().forEach(estampadosNode::put);
        ObjectNode escotesNode = facetsNode.putObject("escotes");
        facets.escotes().forEach(escotesNode::put);
        ObjectNode colorDominantesNode = facetsNode.putObject("colorDominantes");
        facets.colorDominantes().forEach(colorDominantesNode::put);
        // Rubros con conteo
        ObjectNode rubrosNode = facetsNode.putObject("rubros");
        r.productos().stream()
            .filter(p -> p.rubro() != null && !p.rubro().isBlank())
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.rubro().toLowerCase(),
                java.util.stream.Collectors.counting()))
            .forEach((rb, cnt) -> rubrosNode.put(rb, cnt.intValue()));
        // Conteo de productos gymrat
        long gymratCount = r.productos().stream().filter(Product::gymrat).count();
        facetsNode.put("gymratCount", (int) gymratCount);

        // Conteo de productos pack/combo (Fase 3/4 — facet "Packs")
        long packCount = r.productos().stream().filter(Product::esPack).count();
        facetsNode.put("packCount", (int) packCount);

        // Marcas y errores
        ObjectNode marcas = meta.putObject("marcas");
        r.conteoPorSitio().forEach(marcas::put);
        if (!r.erroresPorSitio().isEmpty()) {
            ObjectNode errs = meta.putObject("errores");
            r.erroresPorSitio().forEach(errs::put);
        }

        // Productos de la página
        ArrayNode prods = root.putArray("productos");
        for (Product p : pagina) {
            ObjectNode n = prods.addObject();
            n.put("sitio",      safe(p.sitio()));
            n.put("nombre",     safe(p.nombre()));
            n.put("precio",     p.precio());
            n.put("precioOrig", safe(p.precioOriginal()));
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
            n.put("precioUnitario", precioUnitario(p));
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
    // Facets sueltos (para cargar filtros sin productos)
    // ---------------------------------------------------------------
    @GetMapping("/facets")
    public ResponseEntity<ObjectNode> facets() {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        Facets facets = r.facets();

        ObjectNode tallesNode = root.putObject("talles");
        facets.talles().forEach(tallesNode::put);
        ObjectNode generosNode = root.putObject("generos");
        facets.generos().forEach(generosNode::put);
        ObjectNode catsNode = root.putObject("categorias");
        facets.categorias().forEach(catsNode::put);
        ObjectNode marcasNode2 = root.putObject("marcas");
        facets.marcas().forEach(marcasNode2::put);
        ObjectNode badgesNode2 = root.putObject("badges");
        facets.badges().forEach(badgesNode2::put);
        ObjectNode subCategoriasNode2 = root.putObject("subCategorias");
        facets.subCategorias().forEach(subCategoriasNode2::put);
        // Facets de atributos visuales (fashion-image-classification PR6, T6.6)
        ObjectNode fitsNode2 = root.putObject("fits");
        facets.fits().forEach(fitsNode2::put);
        ObjectNode estampadosNode2 = root.putObject("estampados");
        facets.estampados().forEach(estampadosNode2::put);
        ObjectNode escotesNode2 = root.putObject("escotes");
        facets.escotes().forEach(escotesNode2::put);
        ObjectNode colorDominantesNode2 = root.putObject("colorDominantes");
        facets.colorDominantes().forEach(colorDominantesNode2::put);

        // Conteo de productos gymrat
        long gymratCount = r.productos().stream().filter(Product::gymrat).count();
        root.put("gymratCount", (int) gymratCount);

        // Conteo de productos pack/combo (Fase 3/4 — facet "Packs")
        long packCount = r.productos().stream().filter(Product::esPack).count();
        root.put("packCount", (int) packCount);

        return ResponseEntity.ok(root);
    }

    // ---------------------------------------------------------------
    // CSV — descarga todo sin filtrar
    // ---------------------------------------------------------------
    @GetMapping("/csv")
    public ResponseEntity<String> csv() throws Exception {
        String content = service.generarCsv();
        if (content.isBlank()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ofertas.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body("\uFEFF" + content);
    }

    // ---------------------------------------------------------------
    // Gestión de sitios
    // ---------------------------------------------------------------
    @GetMapping("/sitios")
    public ResponseEntity<ObjectNode> getSitios() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode base = root.putArray("base");
        for (var s : config.getSitiosActivos()) {
            ObjectNode n = base.addObject();
            n.put("nombre", s.nombre());
            n.put("url", s.url());
            n.put("tipo", "config");
            n.put("rubro", s.rubro());
        }
        ArrayNode extras = root.putArray("extras");
        for (var s : service.getSitiosExtras()) {
            ObjectNode n = extras.addObject();
            n.put("nombre", s.nombre());
            n.put("url", s.url());
            n.put("plataforma", s.plataforma());
            n.put("tipo", "dinamico");
        }
        root.put("precioMinimo", config.getPrecioMinimo());
        root.put("precioMaximo", config.getPrecioMaximo());
        root.put("moneda", config.getMoneda());
        return ResponseEntity.ok(root);
    }

    @PostMapping("/sitios")
    public ResponseEntity<ObjectNode> agregarSitio(@RequestBody Map<String, String> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String nombre     = body.getOrDefault("nombre", "").trim();
        String url        = body.getOrDefault("url", "").trim();
        String plataforma = body.getOrDefault("plataforma", "tiendanube").trim();
        if (nombre.isBlank() || url.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "nombre y url son obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        if (!url.startsWith("http")) url = "https://" + url;
        service.agregarSitio(nombre, url, plataforma);
        resp.put("ok", true);
        resp.put("mensaje", "Sitio '" + nombre + "' agregado. Corré el scraper para incluirlo.");
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/sitios/{nombre}")
    public ResponseEntity<ObjectNode> eliminarSitio(@PathVariable String nombre) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        boolean ok = service.eliminarSitio(nombre);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "Sitio eliminado" : "Sitio no encontrado");
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/config")
    public ResponseEntity<ObjectNode> updateConfig(@RequestBody Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        if (body.containsKey("precioMinimo")) {
            double v = Double.parseDouble(body.get("precioMinimo").toString());
            config.setPrecioMinimo(v);
            resp.put("precioMinimo", v);
        }
        if (body.containsKey("precioMaximo")) {
            double v = Double.parseDouble(body.get("precioMaximo").toString());
            config.setPrecioMaximo(v);
            resp.put("precioMaximo", v);
        }
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    // ─── ML: tendencias, historial de precios, aplicar/renormalizar y
    // entrenamiento. Bodies in MlEndpoints (backlog A3); the mappings stay
    // here. They were spread across three regions of this file.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/tendencias")
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> tendencias() {
        return mlEndpoints.tendencias();
    }

    @GetMapping("/historial")
    public ResponseEntity<Object> historial(@RequestParam String url) {
        return mlEndpoints.historial(url);
    }

    // ─── Grupos de comparativa por artículo ─────────────────────────────────────

    @GetMapping("/grupos")
    public ResponseEntity<Object> grupos(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sitio,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) String rubro,
            @RequestParam(defaultValue = "2") int minSitios,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        // Filtrar y agrupar
        var filtered = r.productos().stream()
            .filter(p -> q == null || q.isBlank()
                || p.nombre().toLowerCase().contains(q.toLowerCase())
                || (p.marca() != null && p.marca().toLowerCase().contains(q.toLowerCase())))
            .filter(p -> categoria == null || categoria.isBlank()
                || (p.categoria() != null && p.categoria().equalsIgnoreCase(categoria)))
            .filter(p -> rubro == null || rubro.isBlank()
                || (p.rubro() != null && p.rubro().equalsIgnoreCase(rubro)))
            .collect(java.util.stream.Collectors.toList());

        var grupos = grouping.agrupar(filtered, minSitios >= 2);

        // Paginación
        int total     = grupos.size();
        int fromIdx   = Math.min(page * size, total);
        int toIdx     = Math.min(fromIdx + size, total);
        var paginated = grupos.subList(fromIdx, toIdx);

        // Serializar
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = MAPPER.createObjectNode();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        var gruposArr = result.putArray("grupos");

        for (var grupo : paginated) {
            var gNode = gruposArr.addObject();
            gNode.put("nombre",    grupo.getNombre());
            gNode.put("categoria", grupo.getCategoria());
            gNode.put("img",       grupo.getImg());
            gNode.put("sitios",    grupo.sitiosDistintos());
            gNode.put("precioMin", grupo.precioMinimo());
            gNode.put("precioMax", grupo.precioMaximo());
            gNode.put("ahorroPct", Math.round(grupo.ahorroPct() * 10.0) / 10.0);
            var precsArr = gNode.putArray("precios");
            for (var p : grupo.getProductos()) {
                var pNode = precsArr.addObject();
                pNode.put("sitio",  safe(p.sitio()));
                pNode.put("precio", p.precio());
                pNode.put("url",    safe(p.url()));
                pNode.put("img",    safe(p.imagenUrl()));
                if (p.precioOriginal() != null && !p.precioOriginal().isBlank())
                    pNode.put("precioOrig", p.precioOriginal());
                if (p.ml() != null && !p.ml().badge().isBlank())
                    pNode.put("badge", p.ml().badge());
            }
        }
        return ResponseEntity.ok(result);
    }






    @PostMapping("/ml/aplicar")
    public ResponseEntity<Object> mlAplicar() {
        return mlEndpoints.mlAplicar();
    }

    @PostMapping("/ml/renormalizar")
    public ResponseEntity<Object> mlRenormalizar() {
        return mlEndpoints.mlRenormalizar();
    }

    // ─── Presets de financiación ("¿conviene en cuotas?") + recomendación
    // de compra + inflación INDEC. Bodies in FinanciacionEndpoints (backlog
    // A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/financiacion/presets")
    public ResponseEntity<ObjectNode> listarPresets() {
        return financiacionEndpoints.listarPresets();
    }

    @PostMapping("/financiacion/presets")
    public ResponseEntity<ObjectNode> crearPreset(@RequestBody Map<String, Object> body) {
        return financiacionEndpoints.crearPreset(body);
    }

    @PutMapping("/financiacion/presets/{id}/activar")
    public ResponseEntity<ObjectNode> activarPreset(@PathVariable int id) {
        return financiacionEndpoints.activarPreset(id);
    }

    @PutMapping("/financiacion/presets/{id}")
    public ResponseEntity<ObjectNode> editarPreset(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return financiacionEndpoints.editarPreset(id, body);
    }

    @DeleteMapping("/financiacion/presets/{id}")
    public ResponseEntity<ObjectNode> eliminarPreset(@PathVariable int id) {
        return financiacionEndpoints.eliminarPreset(id);
    }

    @GetMapping("/recomendacion")
    public ResponseEntity<Object> recomendacion(@RequestParam String url) {
        return financiacionEndpoints.recomendacion(url);
    }

    @GetMapping("/inflacion")
    public ResponseEntity<Object> inflacion() {
        return financiacionEndpoints.inflacion();
    }

    // ─── Outfits + supplement builder + saved outfits. Bodies in
    // OutfitsEndpoints (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/outfits")
    public ResponseEntity<ObjectNode> outfits(
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(defaultValue = "0") double presupuestoSuplementos) {
        return outfitsEndpoints.outfits(genero, presupuesto, excluir, presupuestoSuplementos);
    }

    @GetMapping("/outfits/builder")
    public ResponseEntity<ObjectNode> outfitsBuilder(
            @RequestParam(required = false) String categorias,
            @RequestParam(required = false, defaultValue = "0") double presupuesto,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false, defaultValue = "") String excluir,
            @RequestParam(required = false, defaultValue = "") String pin,
            @RequestParam(defaultValue = "false") boolean greedy,
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        return outfitsEndpoints.outfitsBuilder(categorias, presupuesto, genero, excluir, pin, greedy, estilo);
    }

    @GetMapping("/suplementos/builder")
    public ResponseEntity<Object> suplementosBuilder(
            @RequestParam(required = false) String tipos,
            @RequestParam(defaultValue = "0") double presupuesto) {
        return outfitsEndpoints.suplementosBuilder(tipos, presupuesto);
    }

    @PostMapping("/outfits/feedback")
    public ResponseEntity<ObjectNode> outfitFeedback(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.outfitFeedback(body);
    }

    @PostMapping("/outfits/save")
    public ResponseEntity<ObjectNode> saveOutfit(@RequestBody Map<String, Object> body) {
        return outfitsEndpoints.saveOutfit(body);
    }

    @GetMapping("/outfits/saved")
    public ResponseEntity<Object> getSavedOutfits() {
        return outfitsEndpoints.getSavedOutfits();
    }

    @DeleteMapping("/outfits/saved/{id}")
    public ResponseEntity<ObjectNode> deleteSavedOutfit(@PathVariable int id) {
        return outfitsEndpoints.deleteSavedOutfit(id);
    }

    @PatchMapping("/outfits/saved/{id}/nombre")
    public ResponseEntity<ObjectNode> renameSavedOutfit(@PathVariable int id,
                                                         @RequestBody Map<String, Object> body) {
        return outfitsEndpoints.renameSavedOutfit(id, body);
    }

    @DeleteMapping("/outfits/feedback")
    public ResponseEntity<ObjectNode> resetOutfitFeedback(
            @RequestParam(required = false, defaultValue = "gym") String estilo) {
        return outfitsEndpoints.resetOutfitFeedback(estilo);
    }

    // ─── Recomendados ("Para ti" feed). Bodies in RecomendadosEndpoints
    // (backlog A3); the mappings stay here.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/recomendados")
    public ResponseEntity<ObjectNode> recomendados(
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false)    String genero,
            @RequestParam(required = false)    String categoria) {
        return recomendadosEndpoints.recomendados(page, size, genero, categoria);
    }

    @PostMapping("/recomendados/feedback")
    public ResponseEntity<ObjectNode> recomendadosFeedback(@RequestBody Map<String, Object> body) {
        return recomendadosEndpoints.recomendadosFeedback(body);
    }

    @PostMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ObjectNode> dismissCategoria(@RequestBody Map<String, String> body) {
        return recomendadosEndpoints.dismissCategoria(body);
    }

    @DeleteMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ObjectNode> undismissCategoria(@RequestParam String categoria) {
        return recomendadosEndpoints.undismissCategoria(categoria);
    }

    // ─── Favoritos. Bodies in FavoritosEndpoints (backlog A3); the mappings
    // stay here. DELETE /api/data is NOT part of that group -- it is written
    // in this region but soft-deletes a catalog product.
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping("/favoritos")
    public ResponseEntity<ArrayNode> getFavoritos() {
        return favoritosEndpoints.getFavoritos();
    }

    @PostMapping("/favoritos")
    public ResponseEntity<ObjectNode> addFavorito(@RequestBody Map<String, String> body) {
        return favoritosEndpoints.addFavorito(body);
    }

    @DeleteMapping("/favoritos")
    public ResponseEntity<ObjectNode> deleteFavorito(@RequestParam String url) {
        return favoritosEndpoints.deleteFavorito(url);
    }

    @DeleteMapping("/data")
    public ResponseEntity<ObjectNode> eliminarProducto(@RequestParam String url) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        db.marcarDescontinuado(url);
        service.eliminarProductoDeMemoria(url);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/favoritos/rescrape")
    public ResponseEntity<ObjectNode> rescrapeFavoritos() {
        return favoritosEndpoints.rescrapeFavoritos();
    }

    @GetMapping("/ml/estado")
    public ResponseEntity<Object> mlEstado() {
        return mlEndpoints.mlEstado();
    }

    @PostMapping("/ml/entrenar")
    public ResponseEntity<Object> mlEntrenar(
            @RequestParam(defaultValue = "false") boolean images,
            @RequestParam(defaultValue = "8") int epochs) {
        return mlEndpoints.mlEntrenar(images, epochs);
    }

    @GetMapping("/ml/resultado")
    public ResponseEntity<Object> mlResultado() {
        return mlEndpoints.mlResultado();
    }

    // ─── Marcas browser ──────────────────────────────────────────────────────────

    @GetMapping("/marcas-browser")
    public ResponseEntity<Object> marcasBrowser(
            @RequestParam(required = false) String rubro,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "count") String sort) {

        var r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        // Sitios conocidos — excluirlos como "marca" si no es un nombre de marca real
        var SITIOS = java.util.Set.of(
            "vcp","sporting","vaypol","freres","batuk","tussy","bulks","bullbenny",
            "midway","eldon","entreno","city","foreverbstrd","forever","dcshoes",
            "compragamer","fullh4rd","maximus","indumentaria","tecnologia","suplementos"
        );

        // Agrupar por marca — incluir cualquier producto con marca no vacía y no-sitio-genérica
        var byMarca = r.productos().stream()
            .filter(p -> {
                String m = p.marca();
                if (m == null || m.isBlank()) return false;
                String ml = m.toLowerCase().trim().replaceAll("[^a-z0-9 ]","");
                if (ml.length() < 2) return false;
                // Excluir si la marca ES el nombre del sitio exacto
                String sl = p.sitio() != null ? p.sitio().toLowerCase().replaceAll("[^a-z0-9]","") : "";
                if (ml.replaceAll(" ","").equals(sl)) return false;
                if (SITIOS.contains(ml.replaceAll(" ",""))) return false;
                return true;
            })
            .filter(p -> rubro == null || rubro.isBlank()
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> q == null || q.isBlank()
                || p.marca().toLowerCase().contains(q.toLowerCase()))
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.marca().trim()
            ));

        var entries = new java.util.ArrayList<>(byMarca.entrySet());
        entries.sort(switch (sort) {
            case "precio_asc"  -> java.util.Comparator.comparingDouble(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().stream().mapToDouble(Product::precio).average().orElse(0));
            case "precio_desc" -> java.util.Comparator.comparingDouble(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().stream().mapToDouble(Product::precio).average().orElse(0)).reversed();
            default -> java.util.Comparator.comparingInt(
                (java.util.Map.Entry<String,java.util.List<Product>> e) ->
                    e.getValue().size()).reversed();
        });

        var result = MAPPER.createArrayNode();
        entries.stream()
            .filter(e -> e.getValue().size() >= 2)  // al menos 2 productos por marca
            .limit(100)
            .forEach(entry -> {
                String marca = entry.getKey();
                var   prods  = entry.getValue();

                double[] sortedP = prods.stream().mapToDouble(Product::precio).sorted().toArray();
                double mediana   = sortedP[sortedP.length / 2];
                String rubroVal  = prods.get(0).rubro() != null ? prods.get(0).rubro() : "indumentaria";

                String topCats = prods.stream()
                    .filter(p -> p.categoria() != null && !p.categoria().isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(
                        Product::categoria, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(
                        (java.util.Map.Entry<String,Long> e2) -> e2.getValue()).reversed())
                    .limit(3).map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.joining(", "));

                Product best = prods.stream()
                    .filter(p -> p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml() != null && p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                String img = best.imagenUrl() != null ? best.imagenUrl() : "";
                if (img.startsWith("//")) img = "https:" + img;

                var node = result.addObject();
                node.put("marca",     marca);
                node.put("count",     prods.size());
                node.put("rubro",     rubroVal);
                node.put("img",       img);
                node.put("mediana",   (long) mediana);
                node.put("precioMin", (long) sortedP[0]);
                node.put("precioMax", (long) sortedP[sortedP.length-1]);
                node.put("topCats",   topCats);

                var pNode = node.putObject("bestPick");
                pNode.put("nombre", safe(best.nombre()));
                pNode.put("precio", best.precio());
                pNode.put("url",    safe(best.url()));
                String pImg = safe(best.imagenUrl());
                if (pImg.startsWith("//")) pImg = "https:" + pImg;
                pNode.put("img",    pImg);
                if (best.ml() != null) {
                    pNode.put("badge",  safe(best.ml().badge()));
                    pNode.put("scoreP", best.ml().scoreP());
                }
            });
        return ResponseEntity.ok(result);
    }

    // ─── Mejores picks por categoría ─────────────────────────────────────────────

    @GetMapping("/mejores")
    public ResponseEntity<Object> mejoresPorCategoria(
            @RequestParam(required = false) String rubro) {

        var r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        // Agrupar productos por categoría
        java.util.Map<String, java.util.List<Product>> byCat = r.productos().stream()
            .filter(p -> p.categoria() != null && !p.categoria().isBlank())
            .filter(p -> rubro == null || rubro.isBlank()
                || rubro.equalsIgnoreCase(p.rubro() != null ? p.rubro() : "indumentaria"))
            .filter(p -> !"infantil".equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
            .collect(java.util.stream.Collectors.groupingBy(Product::categoria));

        var result = MAPPER.createArrayNode();

        byCat.entrySet().stream()
            .sorted((a,b) -> b.getValue().size() - a.getValue().size())
            .limit(40)
            .forEach(entry -> {
                String cat   = entry.getKey();
                var   prods  = entry.getValue();
                if (prods.isEmpty()) return;

                // 1. Mejor precio/calidad: menor composite score con imagen
                Product mejor = prods.stream()
                    .filter(p -> p.ml() != null && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .min(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .orElse(prods.get(0));

                // 2. Premium accesible: segmento premium o standard, composite 30-65
                Product premium = prods.stream()
                    .filter(p -> p.ml() != null
                        && ("premium".equals(p.ml().segment()) || "standard".equals(p.ml().segment()))
                        && p.ml().scoreP() >= 30 && p.ml().scoreP() <= 65
                        && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .findFirst().orElse(null);

                // 3. Mínimo histórico
                Product histLow = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("all_time_low"))
                    .findFirst().orElse(null);

                // 4. Oferta real
                Product oferta = prods.stream()
                    .filter(p -> p.ml() != null && p.ml().badges() != null
                        && p.ml().badges().contains("verified_deal"))
                    .findFirst().orElse(null);

                // Stats de la categoría — computado sobre precio unitario (pack-aware),
                // no sobre precio de estantería, para no penalizar packs genuinos.
                double mediana = prods.stream().mapToDouble(ApiController::precioUnitario)
                    .sorted().skip(prods.size()/2).findFirst().orElse(0);
                String imgCat = mejor.imagenUrl() != null ? mejor.imagenUrl() : "";
                if (imgCat.startsWith("//")) imgCat = "https:" + imgCat;
                String rubroVal = mejor.rubro() != null ? mejor.rubro() : "indumentaria";

                var node = result.addObject();
                node.put("categoria", cat);
                node.put("count",     prods.size());
                node.put("rubro",     rubroVal);
                node.put("imgCat",    imgCat);
                node.put("mediana",   Math.round(mediana));

                var picks = node.putArray("picks");
                java.util.Set<String> incluidos = new java.util.HashSet<>();
                // Highlights curados primero (preservan su etiqueta semántica).
                addMejorPickDedup(picks, mejor,   "valor",    "Mejor precio/calidad", incluidos);
                addMejorPickDedup(picks, premium, "premium",  "Premium accesible",    incluidos);
                addMejorPickDedup(picks, histLow, "histLow",  "Mínimo histórico",     incluidos);
                addMejorPickDedup(picks, oferta,  "oferta",   "Oferta real",          incluidos);
                // Rellenar hasta MAX_PICKS_POR_CATEGORIA con los siguientes mejores por
                // scoreP (con imagen). Así los packs con buen precio unitario entran
                // integrados en la categoría en vez de quedar afuera por el único cupo
                // de "valor" (scoreP ya es unit-price-aware en ml_pipeline).
                java.util.List<Product> ordenados = prods.stream()
                    .filter(p -> p.ml() != null && p.imagenUrl() != null && !p.imagenUrl().isBlank())
                    .sorted(java.util.Comparator.comparingInt(
                        p -> p.ml().scoreP() > 0 ? p.ml().scoreP() : 999))
                    .collect(java.util.stream.Collectors.toList());
                for (Product p : ordenados) {
                    if (picks.size() >= MAX_PICKS_POR_CATEGORIA) break;
                    addMejorPickDedup(picks, p, "top", "Buena compra", incluidos);
                }
            });

        return ResponseEntity.ok(result);
    }

    /**
     * Precio por unidad de un producto (precio de estantería dividido por
     * {@code cantidadUnidades} cuando es un pack). Espeja la fórmula usada en
     * {@code /api/data} (fila del catálogo) para que catálogo, ML y mejores
     * picks compartan una única fuente de verdad. Guard contra división por
     * cero: {@code cantidadUnidades <= 0} cae al precio de estantería.
     */
    static double precioUnitario(Product p) {
        return ProductJson.precioUnitario(p);
    }

    /** Máximo de productos mostrados por categoría en Mejores Picks. */
    private static final int MAX_PICKS_POR_CATEGORIA = 10;

    /**
     * Agrega un pick evitando duplicados por URL (un producto puede calificar para
     * varios highlights, p.ej. ser el "valor" y además "oferta_real"; se muestra
     * una sola vez con la primera etiqueta que le tocó). Ignora {@code null}.
     */
    private void addMejorPickDedup(com.fasterxml.jackson.databind.node.ArrayNode arr,
                                   Product p, String tipo, String label,
                                   java.util.Set<String> incluidos) {
        if (p == null) return;
        String url = p.url() != null ? p.url() : "";
        if (!url.isBlank() && !incluidos.add(url)) return; // ya incluido
        addMejorPick(arr, p, tipo, label);
    }

    private void addMejorPick(com.fasterxml.jackson.databind.node.ArrayNode arr,
                              Product p, String tipo, String label) {
        var n = arr.addObject();
        n.put("tipo",   tipo);
        n.put("label",  label);
        n.put("nombre", safe(p.nombre()));
        n.put("precio", p.precio());
        n.put("cantidadUnidades", p.cantidadUnidades());
        n.put("esPack",     p.esPack());
        n.put("precioUnitario", precioUnitario(p));
        n.put("url",    safe(p.url()));
        String img = safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        n.put("img",    img);
        n.put("sitio",  safe(p.sitio()));
        n.put("marca",  safe(p.marca()));
        if (p.ml() != null) {
            n.put("scoreP",  p.ml().scoreP());
            n.put("badge",   safe(p.ml().badge()));
            n.put("segment", safe(p.ml().segment()));
            n.put("pctil",   p.ml().pctilCategoria());
        }
        if (p.precioOriginal() != null && !p.precioOriginal().isBlank())
            n.put("precioOrig", p.precioOriginal());
    }

    // ─── Grupos de comparativa multi-sitio ──────────────────────────────────────


    // ─── DB Export / Import ──────────────────────────────────────────────────────

    // decouple-services-postgres Batch 3 (task 3.6): the backend no longer
    // resolves a filesystem SQLite path — persistence lives in Postgres
    // (Batch 1, design D1-D3). The old file-based export/import (which
    // downloaded/replaced a `scraper.db` file, backed by the removed
    // `encontrarDbFile()`) has no equivalent for a networked Postgres
    // instance and is retired here rather than left silently broken.
    // A Postgres-native backup/restore flow (pg_dump/pg_restore, an
    // installer/ops concern) is out of scope for this change; these
    // endpoints now answer honestly instead of pretending to work.
    @GetMapping("/db/export")
    public ResponseEntity<Object> exportDb() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .body(java.util.Map.of("error",
                "DB export de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_dump."));
    }

    @PostMapping(value = "/db/import",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> importDb(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile upload) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE)
            .body(java.util.Map.of("error",
                "DB import de archivo ya no aplica: la persistencia es PostgreSQL, no un archivo scraper.db. Usar pg_restore."));
    }

    // ─── Búsqueda precios externos (MercadoLibre API pública) ──────────────────
    @GetMapping("/buscar-externo")
    public ResponseEntity<Object> buscarExterno(
            @RequestParam String q,
            @RequestParam(required = false) String url,
            @RequestParam(defaultValue = "mercadolibre") String sitio) {
        try {
            // Limpiar query: quitar talle, color, genero, codigos — deja marca+modelo
            String cleanQ = limpiarQueryBusqueda(q);
            LOG.info("[API] buscarExterno q='{}' → limpia='{}'", q, cleanQ);

            var results  = new java.util.ArrayList<java.util.Map<String,Object>>();
            var response = new java.util.LinkedHashMap<String,Object>();

            // Siempre devolver la searchUrl para que el frontend pueda mostrar el link
            // Usar listado.mercadolibre.com.ar — URL canónica de Argentina, no redirige
            String mlSlug = cleanQ.toLowerCase()
                .replaceAll("[áàä]","a").replaceAll("[éèë]","e")
                .replaceAll("[íìï]","i").replaceAll("[óòö]","o")
                .replaceAll("[úùü]","u").replaceAll("[ñ]","n")
                .replaceAll("[^a-z0-9\\s-]","").trim()
                .replaceAll("\\s+","-");
            String searchUrl = "https://listado.mercadolibre.com.ar/" + mlSlug;
            response.put("searchUrl", searchUrl);
            response.put("queryUsada", cleanQ);

            if ("mercadolibre".equals(sitio)) {
                String enc = java.net.URLEncoder.encode(cleanQ, java.nio.charset.StandardCharsets.UTF_8);
                var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                        "https://api.mercadolibre.com/sites/MLA/search?q=" + enc + "&limit=8"))
                    .header("Accept","application/json").GET().build();
                var resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(resp.body()).path("results");
                    if (root.isArray()) for (var item : root) {
                        double precio = item.path("price").asDouble(0);
                        if (precio <= 0) continue;
                        var row = new java.util.LinkedHashMap<String,Object>();
                        row.put("titulo",    item.path("title").asText(""));
                        row.put("precio",    precio);
                        row.put("url",       item.path("permalink").asText(""));
                        row.put("thumbnail", item.path("thumbnail").asText(""));
                        row.put("condicion", item.path("condition").asText("new"));
                        row.put("sitio",     "mercadolibre");
                        row.put("fecha",     java.time.LocalDate.now().toString());
                        results.add(row);
                    }
                }
            }
            response.put("resultados", results);
            if (url != null && !url.isBlank() && !results.isEmpty())
                db.guardarPreciosExternos(url, sitio, results);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.warn("[API] buscarExterno error: {}", e.getMessage());
            var fallback = new java.util.LinkedHashMap<String,Object>();
            fallback.put("searchUrl", "https://www.mercadolibre.com.ar/search?q="
                + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
            fallback.put("queryUsada", q);
            fallback.put("resultados", java.util.List.of());
            return ResponseEntity.ok(fallback);
        }
    }

    /**
     * Limpia el nombre de producto para generar una buena search query.
     * Elimina: talles, colores, género, códigos SKU, preposiciones.
     * Mantiene: marca + nombre del modelo.
     */
    private String limpiarQueryBusqueda(String nombre) {
        if (nombre == null || nombre.isBlank()) return "";
        String q = nombre;

        // 1. Quitar talles alfabeticos sueltos (XL, XXL, S, M, L, etc.)
        q = q.replaceAll("(?i)\\b(talle|talla|size)[:\\s]*\\S+", "");
        q = q.replaceAll("(?i)\\b(xs|xxs|s|m|l|xl|xxl|xxxl|3xl)\\b", "");
        q = q.replaceAll("\\b\\d{1,2}([,.]5)?\\b", "");

        // 2. Quitar colores
        q = q.replaceAll("(?i)\\b(negro|negra|blanco|blanca|azul|rojo|roja|verde|gris|beige"
            + "|naranja|amarillo|violeta|marron|celeste|rosa|plateado|dorado"
            + "|tostado|crudo|ivory|navy|khaki|oliva|militar)\\b", "");

        // 3. Quitar genero
        q = q.replaceAll("(?i)\\b(de hombre|de mujer|para hombre|para mujer"
            + "|masculino|femenino|unisex|hombre|mujer)\\b", "");

        // 4. Quitar descriptores genericos
        q = q.replaceAll("(?i)\\b(original|importado|nuevo|nueva|edicion"
            + "|coleccion|temporada|primavera|verano|invierno|fw|ss)\\b", "");

        // 5. Quitar codigos SKU largos (5+ digitos)
        q = q.replaceAll("\\b\\d{5,}\\b", "");

        // 6. Limpiar puntuacion y espacios
        q = q.replaceAll("[,/|()\\[\\]]+", " ");
        q = q.replaceAll("\\s{2,}", " ").trim();

        // 7. Truncar a 60 chars en limite de palabra
        if (q.length() > 60) {
            int cut = q.lastIndexOf(' ', 60);
            q = (cut > 15 ? q.substring(0, cut) : q.substring(0, 60)).trim();
        }
        return q.isBlank() ? nombre.substring(0, Math.min(40, nombre.length())) : q;
    }

    // Helpers de filtrado
    // ---------------------------------------------------------------
    private List<Product> aplicarFiltros(
            List<Product> productos,
            List<String> talles,
            String genero,
            List<String> categorias,
            String q,
            String sitioFiltro,
            List<String> marcaFiltro,
            String badgeFiltro,
            String segmentFiltro,
            String rubroFiltro,
            Boolean gymratFiltro,
            Boolean packFiltro,
            Double precioMinFiltro,
            Double precioMaxFiltro,
            List<String> subCategoriaFiltro,
            String fitFiltro,
            String estampadoFiltro,
            String escoteFiltro,
            String colorDominanteFiltro
    ) {
        return productos.stream()
                .filter(p -> {
                    // Filtro sitio/marca (exacto)
                    if (sitioFiltro != null && !sitioFiltro.isBlank()) {
                        String s = p.sitio() != null ? p.sitio() : "";
                        if (!s.equalsIgnoreCase(sitioFiltro)) return false;
                    }
                    // Filtro talle: OR — el producto tiene al menos uno de los talles pedidos
                    if (talles != null && !talles.isEmpty()) {
                        if (p.talles() == null || p.talles().isEmpty()) return false;
                        boolean match = talles.stream().anyMatch(t ->
                                p.talles().stream().anyMatch(pt ->
                                        pt.equalsIgnoreCase(t)));
                        if (!match) return false;
                    }
                    // Filtro marca: OR — el producto matchea al menos una marca pedida
                    if (marcaFiltro != null && !marcaFiltro.isEmpty()) {
                        String m = p.marca() != null ? p.marca() : "";
                        boolean match = marcaFiltro.stream().anyMatch(sel -> m.equalsIgnoreCase(sel));
                        if (!match) return false;
                    }
                    // Filtro badge ML — set membership, no longer exact-match (spec
                    // "/api/data?badge= Multi-Badge Filter Semantics"): un producto
                    // matchea si el badge pedido está en su set completo, no solo
                    // si es el principal.
                    if (badgeFiltro != null && !badgeFiltro.isBlank()) {
                        List<String> b = (p.ml() != null && p.ml().badges() != null)
                                ? p.ml().badges() : List.of();
                        boolean match = b.stream().anyMatch(bg -> bg.equalsIgnoreCase(badgeFiltro));
                        if (!match) return false;
                    }
                    // Filtro segment
                    if (segmentFiltro != null && !segmentFiltro.isBlank()) {
                        String seg = (p.ml() != null && p.ml().segment() != null) ? p.ml().segment() : "standard";
                        if (!seg.equalsIgnoreCase(segmentFiltro)) return false;
                    }
                    // Filtro rubro
                    if (rubroFiltro != null && !rubroFiltro.isBlank()) {
                        String rb = p.rubro() != null ? p.rubro() : "indumentaria";
                        if (!rb.equalsIgnoreCase(rubroFiltro)) return false;
                    }
                    // Filtro gymrat
                    if (gymratFiltro != null && gymratFiltro) {
                        if (!p.gymrat()) return false;
                    }
                    // Filtro pack/combo (Fase 5 — espejo del patron gymratFiltro)
                    if (packFiltro != null && packFiltro) {
                        if (!p.esPack()) return false;
                    }
                    // Filtro rango de precio (additive, backward-compatible).
                    // Usa precio unitario (precio/cantidadUnidades), no el precio total del
                    // pack — mismo criterio que precioUnitario expuesto al frontend y que el
                    // pipeline ML, para que un pack de varias unidades no quede excluido/incluido
                    // por su precio total en vez de su precio por unidad.
                    double precioComparable = p.cantidadUnidades() > 0 ? p.precio() / p.cantidadUnidades() : p.precio();
                    if (precioMinFiltro != null && precioComparable < precioMinFiltro) return false;
                    if (precioMaxFiltro != null && precioComparable > precioMaxFiltro) return false;
                    // Filtro género
                    if (genero != null && !genero.isBlank()) {
                        String g = p.genero() != null ? p.genero() : "";
                        if (!g.equalsIgnoreCase(genero)) return false;
                    }
                    // Filtro categoría: OR con prefix matching (Zapatilla → Zapatilla Running)
                    if (categorias != null && !categorias.isEmpty()) {
                        String prodCat = (p.categoria() != null ? p.categoria() : "").toLowerCase();
                        boolean match = categorias.stream().anyMatch(sel -> {
                            String s = sel.toLowerCase();
                            return prodCat.equals(s)
                                || prodCat.startsWith(s + " ")
                                || s.startsWith(prodCat + " ");
                        });
                        if (!match) return false;
                    }
                    // Filtro subCategoria: OR exact match (accent-sensitive, values already stored normalized)
                    if (subCategoriaFiltro != null && !subCategoriaFiltro.isEmpty()) {
                        String sc = p.subCategoria() != null ? p.subCategoria() : "";
                        boolean match = subCategoriaFiltro.stream().anyMatch(sel -> sc.equalsIgnoreCase(sel));
                        if (!match) return false;
                    }
                    // Filtros de atributos visuales (fashion-image-classification PR6,
                    // T6.7/T6.8) — additive, exact match case-insensitive, mirroring
                    // the existing genero/sitio filter shape. Product.visual() is
                    // never null (defaults to VisualAttrs.EMPTY), but guard anyway.
                    Product.VisualAttrs visual = p.visual() != null ? p.visual() : Product.VisualAttrs.EMPTY;
                    if (fitFiltro != null && !fitFiltro.isBlank()
                            && !fitFiltro.equalsIgnoreCase(visual.fit())) return false;
                    if (estampadoFiltro != null && !estampadoFiltro.isBlank()
                            && !estampadoFiltro.equalsIgnoreCase(visual.estampado())) return false;
                    if (escoteFiltro != null && !escoteFiltro.isBlank()
                            && !escoteFiltro.equalsIgnoreCase(visual.escote())) return false;
                    if (colorDominanteFiltro != null && !colorDominanteFiltro.isBlank()
                            && !colorDominanteFiltro.equalsIgnoreCase(visual.colorDominante())) return false;
                    // Búsqueda full-text
                    if (q != null && !q.isBlank()) {
                        String lower = q.toLowerCase();
                        String nombre = p.nombre() != null ? p.nombre().toLowerCase() : "";
                        if (!nombre.contains(lower)) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private List<Product> ordenar(List<Product> productos, String orden) {
        return switch (orden != null ? orden : "precio_asc") {
            case "precio_desc" -> productos.stream()
                    .sorted(Comparator.comparingDouble(Product::precio).reversed())
                    .collect(Collectors.toList());
            case "nombre_asc", "nombre" -> productos.stream()
                    .sorted(Comparator.comparing(p -> p.nombre() != null ? p.nombre().toLowerCase() : ""))
                    .collect(Collectors.toList());
            case "composite", "ml_score" -> productos.stream()
                    .sorted(Comparator.comparingInt(p -> p.ml() != null ? p.ml().scoreP() : 50))
                    .collect(Collectors.toList());
            case "desc_pct" -> productos.stream()
                    .filter(p -> p.tieneDescuento())
                    .sorted(Comparator.comparingDouble((Product p) -> {
                        if (!p.tieneDescuento()) return 0.0;
                        try {
                            double orig = Double.parseDouble(
                                p.precioOriginal().replaceAll("[^0-9.]",""));
                            return orig > 0 ? (orig - p.precio()) / orig : 0.0;
                        } catch (Exception e) { return 0.0; }
                    }).reversed())
                    .collect(Collectors.toList());
            default -> productos.stream()
                    .sorted(Comparator.comparingDouble(Product::precio))
                    .collect(Collectors.toList());
        };
    }

    private String safe(String s) { return ProductJson.safe(s); }

    // ---------------------------------------------------------------
    // LLM Catalog Agent (llm-catalog-nlp) — chat / apply / models, grouped
    // together behind the same future admin-only gate. NOTE (task 5.7, scope
    // id 734): this whole group is the intended insertion point for an
    // admin-only auth guard once user accounts/roles exist — no no-op guard
    // is added now, this comment only marks WHERE it goes.
    //
    // The bodies live in AgentEndpoints (backlog A3); the mappings stay here.
    // ---------------------------------------------------------------

    @PostMapping("/agent/chat")
    public ResponseEntity<Object> agentChat(@RequestBody Map<String, Object> body) {
        return agentEndpoints.agentChat(body);
    }

    @GetMapping("/agent/models")
    public ResponseEntity<Object> agentModels() {
        return agentEndpoints.agentModels();
    }

    @PostMapping("/agent/apply")
    public ResponseEntity<Object> agentApply(@RequestBody ReclassifyProposal body) {
        return agentEndpoints.agentApply(body);
    }

}
