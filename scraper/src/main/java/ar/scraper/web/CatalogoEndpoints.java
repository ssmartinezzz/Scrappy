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
import java.util.List;
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

    CatalogoEndpoints(ScraperService service,
                      ar.scraper.db.DatabaseService db,
                      ScraperConfig config) {
        this.service = service;
        this.db = db;
        this.config = config;
    }

    private String safe(String s) { return ProductJson.safe(s); }

    ResponseEntity<ObjectNode> data(
            int page, int size, List<String> talle, String genero, List<String> categoria,
            String q, String sitio, List<String> marca, String badge, String segment,
            String rubro, Boolean gymrat, String orden, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategoria,
            String fit, String estampado, String escote, String colorDominante
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
    // Facets sueltos (para cargar filtros sin productos)
    // ---------------------------------------------------------------
    ResponseEntity<ObjectNode> facets() {
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
}
