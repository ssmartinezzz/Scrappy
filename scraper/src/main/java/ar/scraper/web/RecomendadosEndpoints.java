package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.catalog.ProductJson;
import ar.scraper.identity.Sujeto;
import ar.scraper.outfits.FeedbackModels;
import ar.scraper.outfits.RecommendationService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "Para ti" personalized feed.
 *
 * <p>design.md (personalized-recommendations-feed) Decision 2: additive endpoints,
 * /api/outfits/feedback stays untouched. The shared taste signal lives in the
 * outfit_feedback_item TABLE (slot="catalog" sentinel here), not a shared URL —
 * {@link FeedbackModels#build} already reads ALL rows regardless of slot, so
 * bidirectional sharing with the outfit-builder requires no extra wiring here.</p>
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). This class holds
 * no request mappings: {@link ApiController} keeps them and delegates here, so
 * the routes and every existing caller are untouched.</p>
 */
class RecomendadosEndpoints {

    private final ScraperService service;
    private final ar.scraper.feedback.FeedbackPort feedback;
    private final RecommendationService recommendationService;
    private final ar.scraper.identity.ActorResolver actorResolver;

    RecomendadosEndpoints(ScraperService service,
                          ar.scraper.feedback.FeedbackPort feedback,
                          RecommendationService recommendationService,
                          ar.scraper.identity.ActorResolver actorResolver) {
        this.service = service;
        this.feedback = feedback;
        this.recommendationService = recommendationService;
        this.actorResolver = actorResolver;
    }

    /**
     * Self-contained duplication of the unisex-bridge + relaxation SHAPE from
     * OutfitService.armar() (steps 0/2, L397-408) and generoElegible()
     * (L325-333). OutfitService is intentionally NOT reused/extracted (locked
     * scope for mejores-picks-fixes). Keep in sync if that pattern changes.
     *
     * Relaxation order per categoria (only advances when the prior step
     * yields zero candidates FOR THAT categoria):
     *   1. own genero (or blank/unisex) + unisex — always eligible.
     *   2. unisex-only (own-genero-exact dropped).
     *   3. opposite-genero (last resort).
     * Infantil is never re-admitted here — RecommendationService.rank()
     * vetoes it unconditionally before/after this relaxation runs.
     */
    private List<Product> broadenGenero(List<Product> base, String generoSolicitado) {
        Map<String, List<Product>> byCategoria = base.stream()
                .collect(Collectors.groupingBy(
                        p -> p.categoria() == null ? "" : p.categoria(),
                        LinkedHashMap::new, Collectors.toList()));

        List<Product> result = new ArrayList<>();
        for (Map.Entry<String, List<Product>> entry : byCategoria.entrySet()) {
            List<Product> productosCategoria = entry.getValue();

            // Paso 1: propio genero (o sin pedido / unisex) + unisex.
            List<Product> step1 = productosCategoria.stream()
                    .filter(p -> generoBridgeMatch(p, generoSolicitado))
                    .collect(Collectors.toList());
            if (!step1.isEmpty()) {
                result.addAll(step1);
                continue;
            }

            // Paso 2: relajar a unisex-only.
            List<Product> step2 = productosCategoria.stream()
                    .filter(p -> "unisex".equalsIgnoreCase(p.genero() != null ? p.genero().trim() : ""))
                    .collect(Collectors.toList());
            if (!step2.isEmpty()) {
                result.addAll(step2);
                continue;
            }

            // Paso 3: relajar a genero opuesto (ultimo recurso).
            result.addAll(productosCategoria);
        }
        return result;
    }

    /** Step 1 match: blank/null genero, "unisex" genero, blank/null/"unisex" pedido, or exact match. */
    private boolean generoBridgeMatch(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (generoSolicitado == null || generoSolicitado.isBlank()) return true;
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true;
        return g.equalsIgnoreCase(generoSolicitado);
    }

    ResponseEntity<ObjectNode> recomendados(int page, int size, String genero, String categoria) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.noContent().build();

        java.util.UUID sujeto = Sujeto.de(actorResolver);
        var feedbackRows = feedback.obtenerOutfitFeedback(sujeto);
        var dismissCats  = feedback.obtenerCategoriaDismiss(sujeto);
        var feedback = FeedbackModels.build(feedbackRows, r.productos(), dismissCats);

        List<Product> candidatos = r.productos();
        if (categoria != null && !categoria.isBlank()) {
            String c = categoria;
            candidatos = candidatos.stream()
                    .filter(p -> c.equalsIgnoreCase(p.categoria()))
                    .collect(Collectors.toList());
        }
        candidatos = broadenGenero(candidatos, genero);

        List<Product> ranked = recommendationService.rank(candidatos, feedback);

        int total = ranked.size();

        // `page` es base 1 y `size` un tamaño, pero los dos llegan de un query
        // param y nada garantiza que respeten eso. Acotar sólo por arriba —que
        // es lo que hacía `Math.min` sola— deja pasar un `page` <= 0 a un índice
        // NEGATIVO: `subList(-24, 0)` tira `IndexOutOfBoundsException`, o sea un
        // HTTP 500 con stack trace disparado desde la URL. Lo mismo un `size`
        // <= 0, por el otro extremo del rango.
        //
        // Se acota en vez de rechazar con 400 porque `/api/data` ya recibe el
        // mismo `page=0` fuera de contrato y sirve la primera página: dos
        // endpoints que leen el mismo parámetro no pueden estar en desacuerdo
        // sobre qué significa un valor inválido, y de las dos conductas la que
        // ya está en producción es la que no se cae.
        int paginaPedida = Math.max(1, page);
        int tamanio      = Math.max(1, size);

        int desde = Math.min((paginaPedida - 1) * tamanio, total);
        int hasta = Math.min(desde + tamanio, total);
        List<Product> pagina = ranked.subList(desde, hasta);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        // El eco es lo que se SIRVIÓ, no lo que se pidió: devolver `page: 0`
        // junto a la primera página le mentiría al cliente sobre dónde está.
        root.put("page",  paginaPedida);
        root.put("size",  tamanio);
        root.put("total", total);
        ArrayNode items = root.putArray("items");
        for (Product p : pagina) {
            ObjectNode n = items.addObject();
            ProductJson.escribir(n, p);
        }
        return ResponseEntity.ok(root);
    }

    ResponseEntity<ObjectNode> recomendadosFeedback(Map<String, Object> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String genero = String.valueOf(body.getOrDefault("genero", ""));

        Object itemsObj = body.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) {
                    Object url   = m.get("url");
                    Object liked = m.get("liked");
                    if (url == null || liked == null) continue; // skip silencioso, mirrors outfits/feedback guard style
                    boolean likedBool = Boolean.parseBoolean(String.valueOf(liked));
                    feedback.guardarOutfitFeedbackItem(Sujeto.de(actorResolver), genero, "catalog",
                            String.valueOf(url), likedBool, "catalog");
                }
            }
        }

        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> dismissCategoria(Map<String, String> body) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        String categoria = body.getOrDefault("categoria", "").trim();
        if (categoria.isBlank()) {
            resp.put("ok", false);
            resp.put("mensaje", "categoria es obligatoria");
            return ResponseEntity.badRequest().body(resp);
        }
        feedback.guardarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }

    ResponseEntity<ObjectNode> undismissCategoria(String categoria) {
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        feedback.borrarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        resp.put("ok", true);
        return ResponseEntity.ok(resp);
    }
}
