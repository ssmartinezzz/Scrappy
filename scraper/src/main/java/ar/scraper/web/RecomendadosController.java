package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.json.ProductJson;
import ar.scraper.security.Sujeto;
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
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

/**
 * The shared taste signal lives in the outfit_feedback_item table (slot="catalog" here), which
 * {@link FeedbackModels#build} reads regardless of slot, so it is shared with the outfit builder
 * without extra wiring.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecomendadosController {

    private final ScraperService service;
    private final ar.scraper.feedback.FeedbackPort feedback;
    private final RecommendationService recommendationService;
    private final ar.scraper.security.ActorResolver actorResolver;

    /**
     * Duplicates the unisex-bridge + relaxation SHAPE of OutfitService.armar()/generoElegible() on
     * purpose (OutfitService is not reused); keep in sync.
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

    private boolean generoBridgeMatch(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (StringUtils.isBlank(generoSolicitado)) return true;
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true;
        return g.equalsIgnoreCase(generoSolicitado);
    }

    @GetMapping("/recomendados")
    public ResponseEntity<ApiResponse<List<ObjectNode>>> recomendados(@RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(required = false)    String genero,
            @RequestParam(required = false)    String categoria) {
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

    @PostMapping("/recomendados/feedback")
    public ResponseEntity<ApiResponse<OpResult>> recomendadosFeedback(@RequestBody Map<String, Object> body) {
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

    @PostMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ApiResponse<OpResult>> dismissCategoria(@RequestBody Map<String, String> body) {
        String categoria = body.getOrDefault("categoria", "").trim();
        if (categoria.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "solicitud_invalida", "categoria es obligatoria");
        }
        feedback.guardarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }

    @DeleteMapping("/recomendados/dismiss-categoria")
    public ResponseEntity<ApiResponse<OpResult>> undismissCategoria(@RequestParam String categoria) {
        feedback.borrarCategoriaDismiss(Sujeto.de(actorResolver), categoria);
        return ResponseEntity.ok(ApiResponse.ok(OpResult.ok()));
    }
}
