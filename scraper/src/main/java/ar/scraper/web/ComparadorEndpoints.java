package ar.scraper.web;

import org.springframework.http.ResponseEntity;

/**
 * Multi-site price comparison: article groups from the live catalog and the
 * external MercadoLibre lookup.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class ComparadorEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(ComparadorEndpoints.class);

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final ar.scraper.aggregator.grouping.GroupingService grouping;

    ComparadorEndpoints(ScraperService service,
                        ar.scraper.db.DatabaseService db,
                        ar.scraper.aggregator.grouping.GroupingService grouping) {
        this.service = service;
        this.db = db;
        this.grouping = grouping;
    }

    private String safe(String s) { return ProductJson.safe(s); }

    // ─── Grupos de comparativa por artículo ─────────────────────────────────────

    ResponseEntity<Object> grupos(String q, String sitio, String categoria, String rubro,
                                  int minSitios, int page, int size) {

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

    // ─── Búsqueda precios externos (MercadoLibre API pública) ──────────────────

    ResponseEntity<Object> buscarExterno(String q, String url, String sitio) {
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
}
