package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link OutfitService.FeedbackModel} shared by the outfit builder
 * surfaces and the "Para ti" feed.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). It lives in its
 * own class rather than inside {@code OutfitsEndpoints} because both the outfit
 * endpoints and the recomendados feed call it — it was never owned by either.
 * Stateless: a pure function of its arguments, which is why the methods are
 * static.</p>
 */
final class FeedbackModels {

    private FeedbackModels() {}

    /**
     * Construye el FeedbackModel a partir de las filas crudas de outfit_feedback_item +
     * el catálogo vivo (join url→Product) + las categorias dismissed feed-wide.
     * Per ADR-1 de outfit-per-item-feedback:
     * - Genero se ignora completamente (scope global, "MUST NOT vary by genero").
     * - URLs que no resuelven contra el catálogo vivo se saltean en silencio (sin
     *   error, sin log) — spec "Feedback references a delisted product".
     * - Cada fila es UN item calificado (slot, url, liked) — no hay broadcast a
     *   otros slots de la misma submission (spec "no-broadcast constraint"). Esto
     *   incluye filas con slot="catalog" (recomendados feed, design.md Decision 2) —
     *   se acumulan exactamente igual que filas de slots del outfit-builder, sin
     *   distinción, porque ambos comparten la misma tabla y el mismo significado
     *   (par marca|categoria con like/dislike).
     * - Orden de construcción: (a) acumular boostLikeCount sobre filas liked=1;
     *   (b) acumular exclude sobre filas liked=0; (c) NO se remueve un par de
     *   boostLikeCount aunque también esté en exclude — el consumidor (OutfitService/
     *   RecommendationService) chequea exclude primero, así que el boost de un par
     *   excluido simplemente nunca se lee (dislike es un veto duro y permanente que
     *   gana sobre cualquier like).
     * - excludeCategoria (Decision 1, personalized-recommendations-feed): eje
     *   SEGUNDO e independiente, poblado directamente desde categoria_dismiss —
     *   no requiere join con el catálogo vivo (no tiene marca asociada).
     */
    static OutfitService.FeedbackModel build(
            List<ar.scraper.db.DatabaseService.OutfitItemRow> rows, List<Product> productos,
            Set<String> dismissCategorias) {
        return build(rows, productos, dismissCategorias, null);
    }

    /**
     * Overload con filtro de estilo (separación de señal de gusto por superficie).
     * allowedEstilos = null → usa TODAS las filas (feed "Para ti", señal global).
     * allowedEstilos = {..} → solo filas cuyo estilo esté en el set. El builder gym
     * pasa {"gym","catalog"} y el casual {"casual","catalog"}: quedan separados entre
     * sí pero ambos siguen consumiendo la señal del feed ("catalog"), preservando el
     * sharing bidireccional del PR #21 sin filtrar gym↔casual.
     */
    static OutfitService.FeedbackModel build(
            List<ar.scraper.db.DatabaseService.OutfitItemRow> rows, List<Product> productos,
            Set<String> dismissCategorias, Set<String> allowedEstilos) {
        Map<String, Product> porUrl = new HashMap<>();
        for (Product p : productos) {
            if (p.url() != null && !p.url().isBlank()) porUrl.put(p.url(), p);
        }

        Map<String, Integer> boostLikeCount = new HashMap<>();
        Set<String> exclude = new HashSet<>();

        // (a) acumular likes por par, sobre filas liked=1 (un item por fila)
        for (var row : rows) {
            if (allowedEstilos != null && !allowedEstilos.contains(row.estilo())) continue;
            if (!row.liked()) continue;
            String url = row.url();
            if (url == null || url.isBlank()) continue;
            Product p = porUrl.get(url);
            if (p == null) continue; // delisted — skip silencioso
            String key = OutfitService.FeedbackModel.keyOf(p);
            boostLikeCount.merge(key, 1, Integer::sum);
        }

        // (b) acumular exclude por par, sobre filas liked=0 — dislike gana siempre
        for (var row : rows) {
            if (allowedEstilos != null && !allowedEstilos.contains(row.estilo())) continue;
            if (row.liked()) continue;
            String url = row.url();
            if (url == null || url.isBlank()) continue;
            Product p = porUrl.get(url);
            if (p == null) continue; // delisted — skip silencioso
            exclude.add(OutfitService.FeedbackModel.keyOf(p));
        }

        Set<String> excludeCategoria = dismissCategorias != null
                ? new HashSet<>(dismissCategorias) : new HashSet<>();

        return new OutfitService.FeedbackModel(exclude, boostLikeCount, excludeCategoria);
    }
}
