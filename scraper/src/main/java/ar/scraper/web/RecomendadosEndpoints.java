package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.json.ProductJson;
import ar.scraper.identity.Sujeto;
import ar.scraper.outfits.FeedbackModels;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.api.PageMeta;
import ar.scraper.web.dto.OpResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * "Para ti" personalized feed. The shared taste signal lives in the outfit_feedback_item table
 * (slot="catalog" here), which {@link FeedbackModels#build} reads regardless of slot, so it is
 * shared with the outfit builder without extra wiring. Mappings live in {@link ApiController}.
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
     * Duplicates the unisex-bridge + relaxation SHAPE of OutfitService.armar()/generoElegible() on
     * purpose (OutfitService is not reused); keep in sync. Per categoria, each step only applies
     * when the previous one yields nothing: own genero + unisex, then unisex-only, then opposite genero.
     * Infantil is never re-admitted: RecommendationService.rank() vetoes it.
     */
    private List<Product> broadenGenero(List<Product> base, String generoSolicitado) {
        Map<String, List<Product>> byCategoria = base.stream()
                .collect(Collectors.groupingBy(
                        p -> p.categoria() == null ? "" : p.categoria(),
                        LinkedHashMap::new, Collectors.toList()));

        List<Product> result = new ArrayList<>();
        for (Map.Entry<String, List<Product>> entry : byCategoria.entrySet()) {
            List<Product> productosCategoria = entry.getValue();

            List<Product> step1 = productosCategoria.stream()
                    .filter(p -> generoBridgeMatch(p, generoSolicitado))
                    .collect(Collectors.toList());
            if (!step1.isEmpty()) {
                result.addAll(step1);
                continue;
            }

            List<Product> step2 = productosCategoria.stream()
                    .filter(p -> "unisex".equalsIgnoreCase(p.genero() != null ? p.genero().trim() : ""))
                    .collect(Collectors.toList());
            if (!step2.isEmpty()) {
                result.addAll(step2);
                continue;
            }

            result.addAll(productosCategoria);
        }
        return result;
    }

    /** Step 1 match: blank/null genero, "unisex" genero, blank/null/"unisex" pedido, or exact match. */
    private boolean generoBridgeMatch(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (StringUtils.isBlank(generoSolicitado)) return true;
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true;
        return g.equalsIgnoreCase(generoSolicitado);
    }

    // Items are ProductJson rows (dynamic shape shared with /api/data), hence ObjectNode.
    ResponseEntity<ApiResponse<List<ObjectNode>>> recomendados(int page, int size, String genero, String categoria) {
        AggregatedResult r = service.getLastResult();
        if (r == null) return ResponseEntity.ok(ApiResponse.page(List.of(), PageMeta.of(Math.max(0, page), Math.max(1, size), 0)));

        java.util.UUID sujeto = Sujeto.de(actorResolver);
        var feedbackRows = feedback.obtenerOutfitFeedback(sujeto);
        var dismissCats  = feedback.obtenerCategoriaDismiss(sujeto);
        var feedback = FeedbackModels.build(feedbackRows, r.productos(), dismissCats);

        List<Product> candidatos = r.productos();
        if (StringUtils.isNotBlank(categoria)) {
            String c = categoria;
            candidatos = candidatos.stream()
                    .filter(p -> c.equalsIgnoreCase(p.categoria()))
                    .collect(Collectors.toList());
        }
        candidatos = broadenGenero(candidatos, genero);

        List<Product> ranked = recommendationService.rank(candidatos, feedback);

        int total = ranked.size();

        // Clamp instead of rejecting: a negative page or size <= 0 would reach subList with a
        // negative index and surface as a 500.
        int paginaPedida = Math.max(0, page);
        int tamanio      = Math.max(1, size);

        int desde = Math.min(paginaPedida * tamanio, total);
        int hasta = Math.min(desde + tamanio, total);
        List<Product> pagina = ranked.subList(desde, hasta);

        List<ObjectNode> items = new ArrayList<>();
        for (Product p : pagina) {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            ProductJson.escribir(n, p);
            items.add(n);
        }
        // Echoes what was SERVED, not what was requested.
        return ResponseEntity.ok(ApiResponse.page(items, PageMeta.of(paginaPedida, tamanio, total)));
    }

    ResponseEntity<ApiResponse<OpResult>> recomendadosFeedback(Map<String, Object> body) {
        String genero = String.valueOf(body.getOrDefault("genero", ""));

        Object itemsObj = body.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) {
                    Object url   = m.get("url");
                    Object liked = m.get("liked");
                    if (url == null || liked == null) continue; // silent skip, same as outfits/feedback
                    boolean likedBool = Boolean.parseBoolean(String.valueOf(liked));
                    feedback.guardarOutfitFeedbackItem(Sujeto.de(actorResolver), genero, "catalog",
                            String.valueOf(url), likedBool, "catalog");
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    ResponseEntity<ApiResponse<OpResult>> dismissCategoria(Map<String, String> body) {
        String categoria = body.getOrDefault("categoria", "").trim();
        if (categoria.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "categoria es obligatoria");
        }
        feedback.guardarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    ResponseEntity<ApiResponse<OpResult>> undismissCategoria(String categoria) {
        feedback.borrarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
