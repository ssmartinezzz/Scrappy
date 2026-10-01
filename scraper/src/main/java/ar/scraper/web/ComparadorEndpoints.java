package ar.scraper.web;

import ar.scraper.json.ProductJson;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import org.apache.commons.lang3.StringUtils;
import ar.scraper.api.ApiResponse;
import ar.scraper.api.PageMeta;
import ar.scraper.web.dto.ComparadorDtos;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

/** Multi-site price comparison and the external MercadoLibre lookup. Mappings live in {@link ApiController}. */
class ComparadorEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(ComparadorEndpoints.class);

    /** One shared client: a per-request one cost 5.2 ms and a live thread each, and discarded the connection pool. */
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(8))
        .build();

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static final java.util.regex.Pattern SLUG_NO_PERMITIDO =
        java.util.regex.Pattern.compile("[^a-z0-9\\s-]");
    private static final java.util.regex.Pattern SLUG_ESPACIOS =
        java.util.regex.Pattern.compile("\\s+");

    private final ScraperService service;
    private final ar.scraper.catalog.PreciosExternosPort preciosExternos;
    private final CatalogoDerivadoCache derivados;

    ComparadorEndpoints(ScraperService service,
                        ar.scraper.catalog.PreciosExternosPort preciosExternos,
                        ar.scraper.aggregator.grouping.GroupingService grouping) {
        this(service, preciosExternos, new CatalogoDerivadoCache(service, grouping));
    }

    ComparadorEndpoints(ScraperService service,
                        ar.scraper.catalog.PreciosExternosPort preciosExternos,
                        CatalogoDerivadoCache derivados) {
        this.service = service;
        this.preciosExternos = preciosExternos;
        this.derivados = derivados;
    }

    private String safe(String s) { return ProductJson.safe(s); }

    /** {@code page} is 0-based here (unlike /api/data and /api/recomendados). */
    ResponseEntity<ApiResponse<List<ComparadorDtos.Grupo>>> grupos(String q, String sitio, String categoria,
                                                                   String rubro, int minSitios, int page, int size) {
        if (service.getLastResult() == null) return ResponseEntity.noContent().build();

        var grupos = derivados.grupos(GruposKey.de(service.snapshotVersion(), q, categoria, rubro, minSitios >= 2));

        // Site filter runs AFTER grouping, unlike q/categoria/rubro: this endpoint compares one
        // article across sites (minSitios=2), so trimming to one site first would empty every group.
        // Before paging so `total` counts the filtered set.
        if (StringUtils.isNotBlank(sitio)) {
            grupos = grupos.stream()
                .filter(g -> g.getProductos().stream()
                    .anyMatch(p -> p.sitio() != null && p.sitio().equalsIgnoreCase(sitio)))
                .collect(java.util.stream.Collectors.toList());
        }

        int total     = grupos.size();
        int fromIdx   = Math.min(page * size, total);
        int toIdx     = Math.min(fromIdx + size, total);
        var paginated = grupos.subList(fromIdx, toIdx);

        List<ComparadorDtos.Grupo> items = new ArrayList<>();
        for (var grupo : paginated) {
            List<ComparadorDtos.Precio> precios = new ArrayList<>();
            for (var p : grupo.getProductos()) {
                precios.add(new ComparadorDtos.Precio(safe(p.sitio()), p.precio(), safe(p.url()),
                        safe(p.imagenUrl()), p.precioOriginal(),
                        p.ml() != null && !p.ml().badge().isBlank() ? p.ml().badge() : null));
            }
            items.add(new ComparadorDtos.Grupo(grupo.getNombre(), grupo.getCategoria(), grupo.getImg(),
                    grupo.sitiosDistintos(), grupo.precioMinimo(), grupo.precioMaximo(),
                    Math.round(grupo.ahorroPct() * 10.0) / 10.0, precios));
        }
        return ResponseEntity.ok(ApiResponse.page(items, PageMeta.of(page, size, total)));
    }

    ResponseEntity<ApiResponse<ComparadorDtos.BusquedaExterna>> buscarExterno(String q, String url, String sitio) {
        try {
            // Strip size/colour/gender/SKU codes: keep brand + model.
            String cleanQ = limpiarQueryBusqueda(q);
            LOG.info("[API] buscarExterno q='{}' → limpia='{}'", q, cleanQ);

            var results  = new ArrayList<ComparadorDtos.ResultadoExterno>();
            var persistir = new ArrayList<java.util.Map<String, Object>>();

            // The searchUrl is always returned so the frontend can show the link;
            // listado.mercadolibre.com.ar is the canonical AR URL and does not redirect.
            String mlSlug = SLUG_ESPACIOS.matcher(
                    SLUG_NO_PERMITIDO.matcher(
                            ar.scraper.aggregator.text.AccentStripper.strip(cleanQ.toLowerCase()))
                        .replaceAll("").trim())
                .replaceAll("-");
            String searchUrl = "https://listado.mercadolibre.com.ar/" + mlSlug;

            if ("mercadolibre".equals(sitio)) {
                String enc = java.net.URLEncoder.encode(cleanQ, java.nio.charset.StandardCharsets.UTF_8);
                var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                        "https://api.mercadolibre.com/sites/MLA/search?q=" + enc + "&limit=8"))
                    .header("Accept","application/json").GET().build();
                var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var root = MAPPER.readTree(resp.body()).path("results");
                    if (root.isArray()) for (var item : root) {
                        double precio = item.path("price").asDouble(0);
                        if (precio <= 0) continue;
                        var row = new ComparadorDtos.ResultadoExterno(
                                item.path("title").asText(""), precio,
                                item.path("permalink").asText(""),
                                item.path("thumbnail").asText(""),
                                item.path("condition").asText("new"),
                                "mercadolibre", java.time.LocalDate.now().toString());
                        results.add(row);
                        var m = new java.util.LinkedHashMap<String, Object>();
                        m.put("titulo",    row.getTitulo());
                        m.put("precio",    row.getPrecio());
                        m.put("url",       row.getUrl());
                        m.put("thumbnail", row.getThumbnail());
                        m.put("condicion", row.getCondicion());
                        m.put("sitio",     row.getSitio());
                        m.put("fecha",     row.getFecha());
                        persistir.add(m);
                    }
                }
            }
            if (StringUtils.isNotBlank(url) && !results.isEmpty())
                preciosExternos.guardarPreciosExternos(url, sitio, persistir);
            return ResponseEntity.ok(ApiResponse.ok(
                    new ComparadorDtos.BusquedaExterna(searchUrl, cleanQ, results)));
        } catch (Exception e) {
            LOG.warn("[API] buscarExterno error: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.ok(new ComparadorDtos.BusquedaExterna(
                    "https://www.mercadolibre.com.ar/search?q="
                            + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8),
                    q, List.of())));
        }
    }

    /**
     * UNICODE_CHARACTER_CLASS fixes a real bug: Java's default {@code \b} treats accented vowels as
     * non-word characters, so in "Móvil" the "M" became a stray size token and was stripped ("Azulón"
     * left "ón"). These patterns run over the RAW product name, so word boundaries must be Unicode-aware.
     */
    private static final int FLAGS_LIMPIEZA =
        java.util.regex.Pattern.CASE_INSENSITIVE
        | java.util.regex.Pattern.UNICODE_CASE
        | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

    // Application order matters (sizes BEFORE colours).
    private static final java.util.regex.Pattern Q_TALLE_ETIQUETADO =
        java.util.regex.Pattern.compile("\\b(talle|talla|size)[:\\s]*\\S+", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_TALLE_SUELTO =
        java.util.regex.Pattern.compile("\\b(xs|xxs|s|m|l|xl|xxl|xxxl|3xl)\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_NUMERO_CORTO =
        java.util.regex.Pattern.compile("\\b\\d{1,2}([,.]5)?\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_COLOR =
        java.util.regex.Pattern.compile("\\b(negro|negra|blanco|blanca|azul|rojo|roja|verde|gris|beige"
            + "|naranja|amarillo|violeta|marron|celeste|rosa|plateado|dorado"
            + "|tostado|crudo|ivory|navy|khaki|oliva|militar)\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_GENERO =
        java.util.regex.Pattern.compile("\\b(de hombre|de mujer|para hombre|para mujer"
            + "|masculino|femenino|unisex|hombre|mujer)\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_DESCRIPTOR =
        java.util.regex.Pattern.compile("\\b(original|importado|nuevo|nueva|edicion"
            + "|coleccion|temporada|primavera|verano|invierno|fw|ss)\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_SKU_LARGO =
        java.util.regex.Pattern.compile("\\b\\d{5,}\\b", FLAGS_LIMPIEZA);
    private static final java.util.regex.Pattern Q_PUNTUACION =
        java.util.regex.Pattern.compile("[,/|()\\[\\]]+");
    private static final java.util.regex.Pattern Q_ESPACIOS =
        java.util.regex.Pattern.compile("\\s{2,}");

    private String limpiarQueryBusqueda(String nombre) {
        if (StringUtils.isBlank(nombre)) return "";
        String q = nombre;

        q = Q_TALLE_ETIQUETADO.matcher(q).replaceAll("");
        q = Q_TALLE_SUELTO.matcher(q).replaceAll("");
        q = Q_NUMERO_CORTO.matcher(q).replaceAll("");

        q = Q_COLOR.matcher(q).replaceAll("");

        q = Q_GENERO.matcher(q).replaceAll("");

        q = Q_DESCRIPTOR.matcher(q).replaceAll("");

        q = Q_SKU_LARGO.matcher(q).replaceAll("");

        q = Q_PUNTUACION.matcher(q).replaceAll(" ");
        q = Q_ESPACIOS.matcher(q).replaceAll(" ").trim();

        if (q.length() > 60) {
            int cut = q.lastIndexOf(' ', 60);
            q = (cut > 15 ? q.substring(0, cut) : q.substring(0, 60)).trim();
        }
        return q.isBlank() ? nombre.substring(0, Math.min(40, nombre.length())) : q;
    }
}
