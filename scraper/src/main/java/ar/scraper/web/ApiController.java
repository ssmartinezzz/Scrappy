package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.*;
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
    private final ar.scraper.aggregator.GroupingService grouping;
    private final ar.scraper.ml.PythonRunner pythonRunner;
    private final OutfitService outfitService;

    public ApiController(ScraperService service,
                         InflacionService inflacionService, ScraperConfig config,
                         ar.scraper.aggregator.ResultAggregator aggregator,
                         ar.scraper.db.DatabaseService db,
                         ar.scraper.aggregator.GroupingService grouping,
                         ar.scraper.ml.PythonRunner pythonRunner,
                         OutfitService outfitService) {
        this.service           = service;
        this.inflacionService  = inflacionService;
        this.config            = config;
        this.aggregator        = aggregator;
        this.db                = db;
        this.grouping          = grouping;
        this.pythonRunner      = pythonRunner;
        this.outfitService     = outfitService;
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
    @GetMapping("/data")
    public ResponseEntity<ObjectNode> data(
            @RequestParam(defaultValue = "1")   int page,
            @RequestParam(defaultValue = "24")  int size,
            @RequestParam(required = false)     List<String> talle,
            @RequestParam(required = false)     String genero,
            @RequestParam(required = false)     List<String> categoria,
            @RequestParam(required = false)     String q,
            @RequestParam(required = false)     String sitio,
            @RequestParam(required = false)     String marca,
            @RequestParam(required = false)     String badge,
            @RequestParam(required = false)     String segment,
            @RequestParam(required = false)     String rubro,
            @RequestParam(required = false)     Boolean gymrat,
            @RequestParam(defaultValue = "precio_asc") String orden
    ) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        // 1. Aplicar filtros
        List<Product> filtrados = aplicarFiltros(r.productos(), talle, genero, categoria, q, sitio, marca, badge, segment, rubro, gymrat);

        // 2. Ordenar
        filtrados = ordenar(filtrados, orden);

        // 3. Paginar
        int total        = filtrados.size();
        int totalPaginas = (int) Math.ceil((double) total / size);
        int desde        = Math.min((page - 1) * size, total);
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
        meta.put("pagina",      page);
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
            ArrayNode tallesArr = n.putArray("talles");
            if (p.talles() != null) p.talles().forEach(tallesArr::add);
            // ML score — siempre serializar para el panel de detalle
            if (p.ml() != null) {
                ObjectNode ml = n.putObject("ml");
                ml.put("badge",      p.ml().badge() != null ? p.ml().badge() : "");
                ml.put("scoreP",     p.ml().scoreP());
                ml.put("ofertaReal", p.ml().ofertaReal());
                ml.put("tendencia",  p.ml().tendencia() != null ? p.ml().tendencia() : "estable");
                ml.put("pctil",      p.ml().pctilCategoria());
                ml.put("zScore",     p.ml().zScore());
                ml.put("segment",    p.ml().segment() != null ? p.ml().segment() : "standard");
            }
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

        // Conteo de productos gymrat
        long gymratCount = r.productos().stream().filter(Product::gymrat).count();
        root.put("gymratCount", (int) gymratCount);

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

    // ---------------------------------------------------------------
    // Tendencias ML
    // ---------------------------------------------------------------
    @GetMapping("/tendencias")
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> tendencias() {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();
        var ml = aggregator.getLastMlOutput();

        // NOT_RUN: pipeline ML falló (null) → 503 con marcador, para que la UI distinga
        // "falló" de "sin datos todavía"
        if (ml == null || ml.isNull()) {
            var err = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            err.put("error", "ml_failed");
            return ResponseEntity.status(503).body(err);
        }

        // EMPTY: corrió pero scores/tendencias no son usables → 204 "sin datos"
        var scoresNode = ml.path("scores");
        var tendNode   = ml.path("tendencias");
        boolean valido = scoresNode.isObject() && !scoresNode.isEmpty() && tendNode.isObject();
        if (!valido) return ResponseEntity.noContent().build();

        // VALID: 200 con payload (deepCopy de tendencias + enriquecido)
        com.fasterxml.jackson.databind.node.ObjectNode result =
                (com.fasterxml.jackson.databind.node.ObjectNode) tendNode.deepCopy();

        // Enriquecer con categoriaStats desde DB
        var catStats = db.cargarCategoriaStats();
        if (!catStats.isEmpty()) {
            var catNode = result.putObject("distribucionCategorias");
            catStats.forEach((cat, payload) -> {
                try { catNode.set(cat, new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)); }
                catch (Exception ignored) {}
            });
        }
        return ResponseEntity.ok(result);
    }

    // ---------------------------------------------------------------
    // Historial de precios
    // ---------------------------------------------------------------
    @GetMapping("/historial")
    public ResponseEntity<Object> historial(@RequestParam String url) {
        var hist = db.cargarHistorial(url);
        if (hist.isEmpty()) return ResponseEntity.noContent().build();
        // Enriquecer con stats básicas del historial
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        var arr  = node.putArray("puntos");
        hist.forEach(h -> {
            var p = arr.addObject();
            p.put("fecha",  (String) h.get("fecha"));
            p.put("precio", ((Number) h.get("precio")).doubleValue());
        });
        if (hist.size() >= 2) {
            double min = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).min().orElse(0);
            double max = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).max().orElse(0);
            double avg = hist.stream().mapToDouble(h -> ((Number) h.get("precio")).doubleValue()).average().orElse(0);
            double first = ((Number) hist.get(0).get("precio")).doubleValue();
            double last  = ((Number) hist.get(hist.size()-1).get("precio")).doubleValue();
            node.put("min", min).put("max", max).put("avg", avg);
            node.put("deltaPct", first > 0 ? Math.round((last - first) / first * 1000.0) / 10.0 : 0);
        }
        return ResponseEntity.ok(node);
    }

    // ---------------------------------------------------------------


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
        var r = service.getLastResult();
        if (r == null) return ResponseEntity.badRequest()
            .body(java.util.Map.of("error", "No hay datos. Ejecutá un scraping primero."));

        // Re-ejecutar pipeline ML sobre datos actuales en background
        Thread.ofVirtual().start(() -> {
            try {
                String prodJson = aggregator.getMlEnricher().serializarProductos(r.productos());
                var mlOut = aggregator.getPythonRunner().ejecutar(prodJson);
                if (mlOut != null) {
                    var enriquecidos = aggregator.getMlEnricher().enriquecer(r.productos(), mlOut, db);
                    // Persistir categorías refinadas
                    java.util.Map<String,String> catOrig = new java.util.HashMap<>();
                    r.productos().forEach(p -> { if(p.url()!=null) catOrig.put(p.url(), p.categoria()!=null?p.categoria():""); });
                    enriquecidos.forEach(p -> {
                        String orig = catOrig.get(p.url());
                        if (orig != null && !p.categoria().equals(orig))
                            try { db.actualizarCategoria(p.url(), p.categoria()); } catch(Exception ignored){}
                    });
                    aggregator.setLastMlOutput(mlOut);
                    db.guardarMlOutput(mlOut);
                    LOG.info("[ML/aplicar] Pipeline re-aplicado: {} productos refinados", enriquecidos.size());
                }
            } catch (Exception e) {
                LOG.warn("[ML/aplicar] Error: {}", e.getMessage());
            }
        });
        return ResponseEntity.ok(java.util.Map.of(
            "status", "started",
            "mensaje", "Pipeline ML re-ejecutándose en background. Refrescá la página en 30 segundos."
        ));
    }


    // ─── Inflación INDEC ─────────────────────────────────────────────────────────


    // ─── Recomendacion de compra ─────────────────────────────────────────────────

    @GetMapping("/recomendacion")
    public ResponseEntity<Object> recomendacion(@RequestParam String url) {
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root   = MAPPER.createObjectNode();
        var historial = db.getHistorialPrecios(url);
        if (historial == null || historial.isEmpty()) {
            root.put("senal",   "sin_datos");
            root.put("mensaje", "Sin historial suficiente para analizar");
            return ResponseEntity.ok(root);
        }
        historial.sort(java.util.Comparator.comparing(h -> h.fecha()));
        double precioActual = historial.get(historial.size()-1).precio();
        double precioMin    = historial.stream().mapToDouble(h -> h.precio()).min().orElse(precioActual);
        double precioMax    = historial.stream().mapToDouble(h -> h.precio()).max().orElse(precioActual);
        double rango        = precioMax - precioMin;
        int    puntoAntiguo = Math.max(0, historial.size() - 13);
        double precioAntiguo  = historial.get(puntoAntiguo).precio();
        double precioAjustado = inflacionService.ajustarPorInflacion(
            precioAntiguo, Math.max(1, historial.size() / 4));
        double cambioReal = precioAjustado > 0
            ? (precioActual - precioAjustado) / precioAjustado * 100.0 : 0.0;
        double pctDelMin  = rango > 0 ? (precioActual - precioMin) / rango * 100.0 : 50.0;
        String tendencia  = "estable";
        if (historial.size() >= 4) {
            double p1 = historial.get(historial.size()-4).precio();
            double p2 = historial.get(historial.size()-1).precio();
            double cambioNominal = p1 > 0 ? (p2 - p1) / p1 * 100.0 : 0;
            if (cambioNominal >  5.0) tendencia = "subiendo";
            else if (cambioNominal < -5.0) tendencia = "bajando";
        }
        String senal, emoji, mensaje;
        int    scoreCompra;
        if (pctDelMin <= 10.0) {
            senal = "comprar_ahora"; emoji = "🔥"; scoreCompra = 95;
            mensaje = "Minimo historico, nunca estuvo mas barato";
        } else if (cambioReal < -8.0 && "bajando".equals(tendencia)) {
            senal = "muy_buen_momento"; emoji = "✅"; scoreCompra = 85;
            mensaje = String.format("Bajo %.0f%% en terminos reales en los ultimos meses", Math.abs(cambioReal));
        } else if (cambioReal < -3.0) {
            senal = "buen_momento"; emoji = "👍"; scoreCompra = 70;
            mensaje = String.format("Precio real cayo %.0f%%, mas barato ajustado por inflacion", Math.abs(cambioReal));
        } else if (cambioReal > 10.0 && "subiendo".equals(tendencia)) {
            senal = "esperar"; emoji = "⚠"; scoreCompra = 20;
            mensaje = String.format("Subio %.0f%% mas que la inflacion, puede bajar", cambioReal);
        } else if (pctDelMin >= 80.0) {
            senal = "caro"; emoji = "❌"; scoreCompra = 15;
            mensaje = "Precio en maximo historico, esperar mejor momento";
        } else {
            senal = "precio_normal"; emoji = "📊"; scoreCompra = 50;
            mensaje = "Precio en rango habitual sin senal fuerte";
        }
        root.put("senal",      senal);
        root.put("emoji",      emoji);
        root.put("mensaje",    mensaje);
        root.put("scoreCompra", scoreCompra);
        root.put("cambioReal",  Math.round(cambioReal * 10.0) / 10.0);
        root.put("pctDelMin",   (int) Math.round(pctDelMin));
        root.put("precioMin",   precioMin);
        root.put("precioMax",   precioMax);
        root.put("tendencia",   tendencia);
        root.put("inflacionMensual",    inflacionService.getInflacionMensual());
        root.put("inflacionInteranual", inflacionService.getInflacionInteranual());
        root.put("puntosHistorial",     historial.size());
        return ResponseEntity.ok(root);
    }


    @GetMapping("/inflacion")
    public ResponseEntity<Object> inflacion() {
        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = MAPPER.createObjectNode();
        root.put("mensual",     inflacionService.getInflacionMensual());
        root.put("interanual",  inflacionService.getInflacionInteranual());
        root.put("acumulada3m", inflacionService.getInflacion3m());
        root.put("actualizado", inflacionService.getUltimaActualizacion());
        var hist = root.putArray("historial");
        inflacionService.getHistorial().stream().limit(13).forEach(d -> {
            var n = hist.addObject();
            n.put("fecha",    d.fecha());
            n.put("valor",    d.valor());
            n.put("variacion", Math.round(d.variacionMensual() * 10.0) / 10.0);
        });
        return ResponseEntity.ok(root);
    }

    // ─── Outfits (armador Gym) ───────────────────────────────────────────────────

    @GetMapping("/outfits")
    public ResponseEntity<ObjectNode> outfits(
            @RequestParam(required = false) String genero) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        var feedbackRows = db.obtenerOutfitFeedback();
        var feedback = buildFeedbackModel(feedbackRows, r.productos());

        OutfitService.Outfit outfit = outfitService.armar(r.productos(), genero, "gym", feedback);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("genero",  outfit.genero());
        root.put("partial", outfit.partial());
        ArrayNode slotsArr = root.putArray("slots");
        for (var pick : outfit.slots()) {
            ObjectNode n = slotsArr.addObject();
            n.put("slot",      pick.slot());
            n.put("sitio",     safe(pick.sitio()));
            n.put("nombre",    safe(pick.nombre()));
            n.put("precio",    pick.precio());
            n.put("url",       safe(pick.url()));
            n.put("img",       safe(pick.img()));
            n.put("categoria", safe(pick.categoria()));
            n.put("marca",     safe(pick.marca()));
        }

        ArrayNode suplArr = root.putArray("suplementos");
        for (var pick : outfitService.armarComboSuplementos(r.productos())) {
            ObjectNode n = suplArr.addObject();
            n.put("tipo",   pick.tipo());
            n.put("sitio",  safe(pick.sitio()));
            n.put("nombre", safe(pick.nombre()));
            n.put("precio", pick.precio());
            n.put("url",    safe(pick.url()));
            n.put("img",    safe(pick.img()));
            n.put("marca",  safe(pick.marca()));
        }
        return ResponseEntity.ok(root);
    }

    /**
     * Construye el FeedbackModel a partir de las filas crudas de outfit_feedback +
     * el catálogo vivo (join url→Product). Per ADR-1 de outfit-recommendation-quality:
     * - Genero se ignora completamente (scope global, "MUST NOT vary by genero").
     * - URLs que no resuelven contra el catálogo vivo se saltean en silencio (sin
     *   error, sin log) — spec "Feedback references a delisted product".
     * - Orden de construcción: (a) acumular boostLikeCount sobre filas liked=1;
     *   (b) acumular exclude sobre filas liked=0; (c) NO se remueve un par de
     *   boostLikeCount aunque también esté en exclude — el consumidor (OutfitService)
     *   chequea exclude primero, así que el boost de un par excluido simplemente
     *   nunca se lee (dislike es un veto duro y permanente que gana sobre cualquier like).
     */
    private OutfitService.FeedbackModel buildFeedbackModel(
            List<ar.scraper.db.DatabaseService.OutfitFeedbackRow> rows, List<Product> productos) {
        Map<String, Product> porUrl = new HashMap<>();
        for (Product p : productos) {
            if (p.url() != null && !p.url().isBlank()) porUrl.put(p.url(), p);
        }

        Map<String, Integer> boostLikeCount = new HashMap<>();
        Set<String> exclude = new HashSet<>();

        // (a) acumular likes por par, sobre filas liked=1
        // Nota: Arrays.asList (NO List.of) — los slots vacíos vienen como null y
        // List.of lanza NPE sobre elementos null (la mayoría de las filas reales
        // de outfit_feedback no llenan los 4 slots a la vez).
        for (var row : rows) {
            if (!row.liked()) continue;
            for (String url : Arrays.asList(row.torsoUrl(), row.piernasUrl(),
                    row.calzadoUrl(), row.accesorioUrl())) {
                if (url == null || url.isBlank()) continue;
                Product p = porUrl.get(url);
                if (p == null) continue; // delisted — skip silencioso
                String key = OutfitService.FeedbackModel.keyOf(p);
                boostLikeCount.merge(key, 1, Integer::sum);
            }
        }

        // (b) acumular exclude por par, sobre filas liked=0 — dislike gana siempre
        for (var row : rows) {
            if (row.liked()) continue;
            for (String url : Arrays.asList(row.torsoUrl(), row.piernasUrl(),
                    row.calzadoUrl(), row.accesorioUrl())) {
                if (url == null || url.isBlank()) continue;
                Product p = porUrl.get(url);
                if (p == null) continue; // delisted — skip silencioso
                exclude.add(OutfitService.FeedbackModel.keyOf(p));
            }
        }

        return new OutfitService.FeedbackModel(exclude, boostLikeCount);
    }

    @PostMapping("/outfits/feedback")
    public ResponseEntity<ObjectNode> outfitFeedback(@RequestBody Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String genero = String.valueOf(body.getOrDefault("genero", ""));
        boolean liked = Boolean.parseBoolean(String.valueOf(body.getOrDefault("liked", "false")));

        Map<String, String> urlPorSlot = new HashMap<>();
        Object slotsObj = body.get("slots");
        if (slotsObj instanceof List<?> slots) {
            for (Object o : slots) {
                if (o instanceof Map<?, ?> m) {
                    Object slot = m.get("slot");
                    Object url  = m.get("url");
                    if (slot != null && url != null) {
                        urlPorSlot.put(String.valueOf(slot), String.valueOf(url));
                    }
                }
            }
        }

        db.guardarOutfitFeedback(genero, liked,
                urlPorSlot.get(OutfitService.SLOT_TORSO),
                urlPorSlot.get(OutfitService.SLOT_PIERNAS),
                urlPorSlot.get(OutfitService.SLOT_CALZADO),
                urlPorSlot.get(OutfitService.SLOT_ACCESORIO));
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    // ─── Favoritos ──────────────────────────────────────────────────────────────

    @GetMapping("/favoritos")
    public ResponseEntity<ArrayNode> getFavoritos() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (var f : db.listarFavoritos()) {
            String url = f.get("url");
            ObjectNode n = arr.addObject();
            // Si tenemos el producto en la DB, volcamos sus campos con la misma
            // forma que /api/data (precio, img, ml, etc.) para que DetailPanel
            // pueda mostrarlo sin pedir nada extra.
            db.obtenerProducto(url).ifPresent(p -> escribirProducto(n, p));
            n.put("url",    url);
            n.put("sitio",  safe(f.get("sitio")));
            n.put("nombre", n.has("nombre") && !n.get("nombre").asText().isBlank()
                    ? n.get("nombre").asText() : safe(f.get("nombre")));
            n.put("addedAt",       safe(f.get("added_at")));
            n.put("lastCheckedAt", safe(f.get("last_checked_at")));
            n.put("descontinuado", !db.esProductoActivo(url));
        }
        return ResponseEntity.ok(arr);
    }

    /** Mismo formato que la lista de /api/data, para reuso en DetailPanel. */
    private void escribirProducto(ObjectNode n, Product p) {
        n.put("sitio",      safe(p.sitio()));
        n.put("nombre",     safe(p.nombre()));
        n.put("precio",     p.precio());
        n.put("precioOrig", safe(p.precioOriginal()));
        n.put("descuento",  p.tieneDescuento());
        String img = safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        n.put("img",        img);
        n.put("categoria",  safe(p.categoria()));
        n.put("genero",     safe(p.genero()));
        n.put("marca",      safe(p.marca()));
        n.put("rubro",      p.rubro() != null ? p.rubro() : "indumentaria");
        ArrayNode tallesArr = n.putArray("talles");
        if (p.talles() != null) p.talles().forEach(tallesArr::add);
        if (p.ml() != null) {
            ObjectNode ml = n.putObject("ml");
            ml.put("badge",      p.ml().badge() != null ? p.ml().badge() : "");
            ml.put("scoreP",     p.ml().scoreP());
            ml.put("ofertaReal", p.ml().ofertaReal());
            ml.put("tendencia",  p.ml().tendencia() != null ? p.ml().tendencia() : "estable");
            ml.put("pctil",      p.ml().pctilCategoria());
            ml.put("zScore",     p.ml().zScore());
            ml.put("segment",    p.ml().segment() != null ? p.ml().segment() : "standard");
        }
    }

    @PostMapping("/favoritos")
    public ResponseEntity<ObjectNode> addFavorito(@RequestBody Map<String, String> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String url    = body.getOrDefault("url", "").trim();
        String sitio  = body.getOrDefault("sitio", "").trim();
        String nombre = body.getOrDefault("nombre", "").trim();
        if (url.isBlank() || sitio.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "url y sitio obligatorios");
            return ResponseEntity.badRequest().body(resp);
        }
        db.guardarFavorito(url, sitio, nombre);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/favoritos")
    public ResponseEntity<ObjectNode> deleteFavorito(@RequestParam String url) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        db.eliminarFavorito(url);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/favoritos/rescrape")
    public ResponseEntity<ObjectNode> rescrapeFavoritos() {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        boolean ok = service.rescrapearFavoritos();
        resp.put("iniciado", ok);
        resp.put("mensaje", ok ? "Rescrape de favoritos iniciado"
                               : "Ya hay un scraping en curso");
        return ResponseEntity.ok(resp);
    }

    // ─── ML Training ─────────────────────────────────────────────────────────────

    @GetMapping("/ml/estado")
    public ResponseEntity<Object> mlEstado() {
        java.io.File modelsDir = new java.io.File("_models");
        java.io.File textModel = new java.io.File(modelsDir, "text_classifier.pkl");
        java.io.File imgModel  = new java.io.File(modelsDir, "image_model.pt");
        java.io.File textMeta  = new java.io.File(modelsDir, "text_meta.json");

        var MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        var root   = MAPPER.createObjectNode();
        root.put("hasTextModel",  textModel.exists());
        root.put("hasImageModel", imgModel.exists());
        if (textMeta.exists()) {
            try {
                var meta = MAPPER.readTree(textMeta);
                root.set("textMeta", meta);
            } catch (Exception ignored) {}
        }
        var ts = pythonRunner.getTrainingStatus();
        ObjectNode training = root.putObject("training");
        training.put("running",   ts.running());
        training.put("phase",     ts.phase());
        training.put("pct",       ts.pct());
        training.put("msg",       ts.msg());
        training.put("startedAt", ts.startedAt() != null ? ts.startedAt() : "");
        return ResponseEntity.ok(root);
    }

    @PostMapping("/ml/entrenar")
    public ResponseEntity<Object> mlEntrenar(
            @RequestParam(defaultValue = "false") boolean images,
            @RequestParam(defaultValue = "8") int epochs) {

        if (pythonRunner.isTrainingRunning())
            return ResponseEntity.badRequest()
                .body(java.util.Map.of("error", "Entrenamiento ya en curso"));

        String dbPath = encontrarDbFile().getAbsolutePath();
        pythonRunner.entrenarEnBackground(dbPath, true, images, epochs);
        return ResponseEntity.ok(java.util.Map.of("status", "started"));
    }

    @GetMapping("/ml/resultado")
    public ResponseEntity<Object> mlResultado() {
        var s = pythonRunner.getTrainingStatus();
        return ResponseEntity.ok(java.util.Map.of(
            "running", s.running(),
            "phase",   s.phase(),
            "pct",     s.pct(),
            "msg",     s.msg(),
            "done",    !s.running() && !"idle".equals(s.phase())
        ));
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
                    .filter(p -> p.ml() != null
                        && "precio_historico_bajo".equals(p.ml().badge()))
                    .findFirst().orElse(null);

                // 4. Oferta real
                Product oferta = prods.stream()
                    .filter(p -> p.ml() != null
                        && "oferta_real".equals(p.ml().badge()))
                    .findFirst().orElse(null);

                // Stats de la categoría
                double mediana = prods.stream().mapToDouble(Product::precio)
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
                addMejorPick(picks, mejor,   "valor",    "Mejor precio/calidad");
                if (premium  != null) addMejorPick(picks, premium,  "premium",  "Premium accesible");
                if (histLow  != null) addMejorPick(picks, histLow,  "histLow",  "Mínimo histórico");
                if (oferta   != null) addMejorPick(picks, oferta,   "oferta",   "Oferta real");
            });

        return ResponseEntity.ok(result);
    }

    private void addMejorPick(com.fasterxml.jackson.databind.node.ArrayNode arr,
                              Product p, String tipo, String label) {
        var n = arr.addObject();
        n.put("tipo",   tipo);
        n.put("label",  label);
        n.put("nombre", safe(p.nombre()));
        n.put("precio", p.precio());
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

    @GetMapping("/db/export")
    public ResponseEntity<org.springframework.core.io.Resource> exportDb() {
        try {
            // Buscar el archivo de base de datos
            java.io.File dbFile = encontrarDbFile();
            if (dbFile == null || !dbFile.exists())
                return ResponseEntity.notFound().build();
            var resource = new org.springframework.core.io.FileSystemResource(dbFile);
            String filename = "scraper-" + java.time.LocalDate.now() + ".db";
            return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(dbFile.length())
                .body(resource);
        } catch (Exception e) {
            LOG.warn("[API] exportDb error: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/db/import",
                 consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> importDb(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile upload) {
        if (service.getStatus().name().equals("RUNNING"))
            return ResponseEntity.badRequest()
                .body(java.util.Map.of("error","No se puede importar mientras el scraping está en curso"));
        try {
            java.io.File dbFile = encontrarDbFile();
            if (dbFile == null) dbFile = new java.io.File("scraper.db");
            // Backup del archivo actual
            java.io.File backup = new java.io.File(dbFile.getParentFile(),
                "scraper-backup-" + java.time.LocalDateTime.now().toString().replace(":","") + ".db");
            if (dbFile.exists()) dbFile.renameTo(backup);
            // Escribir el nuevo archivo
            upload.transferTo(dbFile);
            LOG.info("[API] DB importada: {} bytes (backup: {})", upload.getSize(), backup.getName());
            return ResponseEntity.ok(java.util.Map.of(
                "ok", true,
                "backup", backup.getName(),
                "bytes", upload.getSize()
            ));
        } catch (Exception e) {
            LOG.warn("[API] importDb error: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    private java.io.File encontrarDbFile() {
        String[] candidates = { "scraper.db", "data/scraper.db",
            System.getProperty("user.dir") + "/scraper.db" };
        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) return f;
        }
        return new java.io.File("scraper.db");
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
            String marcaFiltro,
            String badgeFiltro,
            String segmentFiltro,
            String rubroFiltro,
            Boolean gymratFiltro
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
                    // Filtro marca
                    if (marcaFiltro != null && !marcaFiltro.isBlank()) {
                        String m = p.marca() != null ? p.marca() : "";
                        if (!m.equalsIgnoreCase(marcaFiltro)) return false;
                    }
                    // Filtro badge ML
                    if (badgeFiltro != null && !badgeFiltro.isBlank()) {
                        String b = (p.ml() != null && p.ml().badge() != null) ? p.ml().badge() : "";
                        if (!b.equalsIgnoreCase(badgeFiltro)) return false;
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

    private String safe(String s) { return s != null ? s : ""; }
}
