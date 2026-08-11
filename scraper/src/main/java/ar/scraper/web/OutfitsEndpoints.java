package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outfit builder surfaces (gym + budget-aware), the supplement builder, the
 * per-item feedback writes and saved outfits.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class OutfitsEndpoints {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(OutfitsEndpoints.class);

    private final ScraperService service;
    private final ar.scraper.db.DatabaseService db;
    private final OutfitService outfitService;

    OutfitsEndpoints(ScraperService service,
                     ar.scraper.db.DatabaseService db,
                     OutfitService outfitService) {
        this.service = service;
        this.db = db;
        this.outfitService = outfitService;
    }

    private String safe(String s) { return s != null ? s : ""; }

    // ─── Outfits (armador Gym) ───────────────────────────────────────────────────

    ResponseEntity<ObjectNode> outfits(String genero,
                                       double presupuesto,
                                       String excluir,
                                       double presupuestoSuplementos) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> excluirUrls = excluir.isBlank() ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        var feedbackRows = db.obtenerOutfitFeedback();
        var dismissCats  = db.obtenerCategoriaDismiss();
        // Gym surface: gym feedback + shared feed signal ("catalog"), never casual.
        var feedback = FeedbackModels.build(feedbackRows, r.productos(), dismissCats, Set.of("gym", "catalog"));

        OutfitService.Outfit outfit = outfitService.armar(r.productos(), genero, "gym", feedback,
                presupuesto, excluirUrls);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("genero",              outfit.genero());
        root.put("partial",             outfit.partial());
        root.put("totalEstimado",       outfit.totalEstimado());
        root.put("presupuestoExcedido", outfit.presupuestoExcedido());
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

        var suplementosList = outfitService.armarComboSuplementos(r.productos(), presupuestoSuplementos);
        double totalSuplementos = suplementosList.stream()
                .mapToDouble(OutfitService.SupplementPick::precio).sum();
        root.put("totalSuplementos", totalSuplementos);

        ArrayNode suplArr = root.putArray("suplementos");
        for (var pick : suplementosList) {
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

    // ─── Budget-Aware Outfit Builder ─────────────────────────────────────────────

    /**
     * Builds the globally-optimal product combination for the requested categories
     * within a hard budget ceiling (MCKP algorithm in {@link OutfitService}).
     *
     * <p>Validation (400):
     * <ul>
     *   <li>missing or blank {@code categorias}</li>
     *   <li>{@code presupuesto} ≤ 0</li>
     *   <li>no valid categories remain after filtering against {@link OutfitService#KNOWN_CATEGORIAS}</li>
     *   <li>more than 10 categories requested (bounds worst-case K^N enumeration)</li>
     * </ul>
     *
     * <p>No-fit is NOT an error — returns HTTP 200 with {@code noCumplePresupuesto:true}
     * and an empty {@code slots} array.
     */
    ResponseEntity<ObjectNode> outfitsBuilder(String categorias,
                                              double presupuesto,
                                              String genero,
                                              String excluir,
                                              String pin,
                                              boolean greedy,
                                              String estilo) {

        ObjectNode err = JsonNodeFactory.instance.objectNode();

        // Normalize estilo to the only builder surfaces {gym, casual}. Anything else
        // (blank, "null", or the reserved feed bucket "catalog") falls back to "gym".
        // Guards FeedbackModels.build's Set.of(estilo, "catalog") from an
        // IllegalArgumentException on duplicate elements when estilo == "catalog".
        estilo = "casual".equalsIgnoreCase(estilo) ? "casual" : "gym";

        // Validate categorias
        if (categorias == null || categorias.isBlank()) {
            err.put("error", "Missing required parameter: categorias");
            return ResponseEntity.badRequest().body(err);
        }

        // Validate presupuesto
        if (presupuesto <= 0) {
            err.put("error", "presupuesto must be a positive number");
            return ResponseEntity.badRequest().body(err);
        }

        // Parse, filter unknowns, deduplicate
        List<String> catList = Arrays.stream(categorias.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .filter(OutfitService.KNOWN_CATEGORIAS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (catList.isEmpty()) {
            err.put("error", "No valid categories provided. Use canonical category names.");
            return ResponseEntity.badRequest().body(err);
        }

        if (catList.size() > 20) {
            err.put("error", "Too many categories (max 20 allowed)");
            return ResponseEntity.badRequest().body(err);
        }

        // Parse excluir CSV → Set (temporary per-request exclusion, not persisted)
        Set<String> excluirUrls = (excluir == null || excluir.isBlank())
                ? Set.of()
                : Arrays.stream(excluir.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());

        // Parse pin CSV → ordered list of URLs to lock into their sub-slots
        List<String> pinUrls = (pin == null || pin.isBlank())
                ? List.of()
                : Arrays.stream(pin.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toList());

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        var feedbackRows = db.obtenerOutfitFeedback();
        var dismissCats  = db.obtenerCategoriaDismiss();
        // Style-scoped signal: this surface's own estilo + the shared feed ("catalog").
        // gym and casual read disjoint buckets (separated), both see catalog.
        var feedback     = FeedbackModels.build(feedbackRows, r.productos(), dismissCats,
                Set.of(estilo, "catalog"));

        // Resolve pin URLs → Product objects; unresolved URLs are silently dropped
        List<Product> pinned = pinUrls.stream()
                .map(u -> r.productos().stream()
                        .filter(p -> u.equals(p.url()))
                        .findFirst()
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        OutfitService.OutfitBuilderResult result = outfitService.armarPorCategorias(
                r.productos(), catList, presupuesto, genero, feedback, excluirUrls, greedy, pinned, estilo);

        // Determine status per spec API contract
        String status;
        if (result.slots().isEmpty()) {
            status = "no-fit";
        } else if (!result.categoriasVacias().isEmpty()) {
            status = "partial";
        } else {
            status = "ok";
        }

        // Build response JSON
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("status", status);
        ArrayNode slotsArr = root.putArray("slots");
        for (var pick : result.slots()) {
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
        root.put("genero",               safe(result.genero()));
        root.put("presupuesto",          result.presupuesto());
        root.put("totalEstimado",        result.totalEstimado());
        root.put("noCumplePresupuesto",  result.noCumplePresupuesto());
        ArrayNode vaciasArr = root.putArray("categoriasVacias");
        result.categoriasVacias().forEach(vaciasArr::add);
        ArrayNode sinPresupArr = root.putArray("categoriasSinPresupuesto");
        result.categoriasSinPresupuesto().forEach(sinPresupArr::add);
        if ("no-fit".equals(status)) {
            root.put("reason", "No valid combination fits within the budget.");
            if (result.minimoBudgetNecesario() != null) {
                root.put("minimoBudgetNecesario", result.minimoBudgetNecesario());
            }
        }

        return ResponseEntity.ok(root);
    }

    // ─── Supplement Builder ──────────────────────────────────────────────────────

    /**
     * The supplement subtypes the builder can offer, in combo-assembly order.
     *
     * <p>Pure taxonomy — needs no catalog, so unlike the builder it answers before the
     * first scrape has ever run. The frontend selector used to hard-code this list and
     * its group headings, which meant a new subtype had to be added in two places and a
     * forgotten edit left a type the builder returns and the UI cannot select.</p>
     *
     * <p>Va directo a {@link SupplementCombo} y no vía {@code outfitService}: la lista no
     * depende de ningún estado de instancia, así que ruteársela por un servicio sólo
     * agregaría un colaborador que este endpoint no necesita.</p>
     */
    ResponseEntity<ObjectNode> suplementosTipos() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("tipos");
        for (var t : SupplementCombo.tiposDisponibles()) {
            ObjectNode n = arr.addObject();
            n.put("tipo", t.tipo());
            // grupo nullable = "Otros" del lado del cliente. Se manda explícito como null
            // en vez de omitirlo, para que el cliente no tenga que distinguir "sin grupo"
            // de "campo que no vino".
            if (t.grupo() != null) n.put("grupo", t.grupo());
            else n.putNull("grupo");
        }
        return ResponseEntity.ok(root);
    }

    /**
     * Picks one product per requested supplement type from the in-memory catalog.
     *
     * <p>GET /api/suplementos/builder?tipos=Proteína,Creatina&presupuesto=50000
     *
     * @param tipos       comma-separated supplement type names (required; 400 if blank)
     * @param presupuesto optional budget ceiling; 0 = no limit (default)
     * @return 200 with JSON array, 204 when no scrape data exists, 400 when tipos is blank
     */
    ResponseEntity<Object> suplementosBuilder(String tipos, double presupuesto) {

        if (tipos == null || tipos.isBlank()) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "tipos is required");
            return ResponseEntity.badRequest().body(err);
        }

        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        Set<String> tiposSet = Arrays.stream(tipos.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        if (tiposSet.isEmpty()) {
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("error", "tipos is required");
            return ResponseEntity.badRequest().body(err);
        }

        List<OutfitService.SupplementPick> picks =
                outfitService.armarComboSuplementos(r.productos(), presupuesto, tiposSet);

        Set<String> foundTipos = picks.stream()
                .map(OutfitService.SupplementPick::tipo)
                .collect(Collectors.toSet());
        List<String> sinStock = tiposSet.stream()
                .filter(t -> !foundTipos.contains(t))
                .sorted()
                .collect(Collectors.toList());

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode arr = root.putArray("picks");
        for (var pick : picks) {
            ObjectNode n = arr.addObject();
            n.put("tipo",   pick.tipo());
            n.put("sitio",  safe(pick.sitio()));
            n.put("nombre", safe(pick.nombre()));
            n.put("precio", pick.precio());
            n.put("url",    safe(pick.url()));
            n.put("img",    safe(pick.img()));
            n.put("marca",  safe(pick.marca()));
        }
        ArrayNode sinStockArr = root.putArray("sinStock");
        sinStock.forEach(sinStockArr::add);
        return ResponseEntity.ok(root);
    }

    ResponseEntity<ObjectNode> outfitFeedback(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String genero = String.valueOf(body.getOrDefault("genero", ""));
        // estilo separa la señal por superficie (gym | casual). Default "gym" para
        // back-compat con clientes que no lo mandan.
        String estilo = String.valueOf(body.getOrDefault("estilo", "gym"));
        if (estilo.isBlank() || "null".equals(estilo)) estilo = "gym";

        Object itemsObj = body.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) {
                    Object slot  = m.get("slot");
                    Object url   = m.get("url");
                    Object liked = m.get("liked");
                    if (slot == null || url == null || liked == null) continue; // skip silencioso, mirrors existing null-guard style
                    boolean likedBool = Boolean.parseBoolean(String.valueOf(liked));
                    db.guardarOutfitFeedbackItem(genero, String.valueOf(slot), String.valueOf(url), likedBool, estilo);
                }
            }
        }

        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    // ─── Outfits guardados ───────────────────────────────────────────────────────

    ResponseEntity<ObjectNode> saveOutfit(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        try {
            String nombre = String.valueOf(body.getOrDefault("nombre", "Outfit")).trim();
            Object slotsObj = body.get("slots");
            Object suplObj  = body.get("suplementos");
            double totalEstimado = body.containsKey("totalEstimado")
                    ? Double.parseDouble(String.valueOf(body.get("totalEstimado"))) : 0.0;
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String slotsJson = mapper.writeValueAsString(slotsObj != null ? slotsObj : List.of());
            String suplJson  = suplObj != null ? mapper.writeValueAsString(suplObj) : null;
            int id = db.guardarOutfit(nombre, slotsJson, suplJson, totalEstimado);
            if (id < 0) {
                resp.put("ok", false);
                resp.put("mensaje", "No se pudo guardar el outfit");
                return ResponseEntity.internalServerError().body(resp);
            }
            resp.put("ok", true);
            resp.put("id", id);
            resp.put("nombre", nombre);
            resp.put("totalEstimado", totalEstimado);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            LOG.warn("[API] saveOutfit error: {}", e.getMessage());
            resp.put("ok", false);
            resp.put("mensaje", e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    ResponseEntity<Object> getSavedOutfits() {
        return ResponseEntity.ok(db.obtenerOutfitsGuardados());
    }

    ResponseEntity<ObjectNode> deleteSavedOutfit(int id) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        boolean ok = db.eliminarOutfitGuardado(id);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "Outfit eliminado" : "Outfit no encontrado");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    ResponseEntity<ObjectNode> renameSavedOutfit(int id, Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        if (nombre.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "nombre es obligatorio");
            return ResponseEntity.badRequest().body(resp);
        }
        boolean ok = db.renombrarOutfit(id, nombre);
        resp.put("ok", ok);
        resp.put("mensaje", ok ? "Outfit renombrado" : "Outfit no encontrado");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    ResponseEntity<ObjectNode> resetOutfitFeedback(String estilo) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        // Reset scoped por estilo: gym no borra casual ni la señal del feed ("catalog").
        db.limpiarOutfitFeedback((estilo == null || estilo.isBlank()) ? "gym" : estilo);
        resp.put("ok", true);
        resp.put("mensaje", "Historial de feedback reseteado");
        return ResponseEntity.ok(resp);
    }
}
