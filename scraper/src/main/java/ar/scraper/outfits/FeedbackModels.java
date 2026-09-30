package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Stateless: a pure function of its arguments, which is why the methods are static. */
public final class FeedbackModels {

    private FeedbackModels() {}

    public static OutfitService.FeedbackModel build(
            List<ar.scraper.feedback.OutfitItemRow> rows, List<Product> productos,
            Set<String> dismissCategorias) {
        return build(rows, productos, dismissCategorias, null);
    }

    /**
     * Overload con filtro de estilo (separación de señal de gusto por superficie). allowedEstilos =
     * null → usa TODAS las filas (feed "Para ti", señal global). allowedEstilos = {..} → solo filas
     * cuyo estilo esté en el set.
     */
    public static OutfitService.FeedbackModel build(
            List<ar.scraper.feedback.OutfitItemRow> rows, List<Product> productos,
            Set<String> dismissCategorias, Set<String> allowedEstilos) {
        Map<String, Product> porUrl = new HashMap<>();
        for (Product p : productos) {
            if (StringUtils.isNotBlank(p.url())) porUrl.put(p.url(), p);
        }

        Map<String, Integer> boostLikeCount = new HashMap<>();
        Set<String> exclude = new HashSet<>();

        for (var row : rows) {
            if (allowedEstilos != null && !allowedEstilos.contains(row.estilo())) continue;
            if (!row.liked()) continue;
            String url = row.url();
            if (StringUtils.isBlank(url)) continue;
            Product p = porUrl.get(url);
            if (p == null) continue; // delisted — skip silencioso
            String key = OutfitService.FeedbackModel.keyOf(p);
            boostLikeCount.merge(key, 1, Integer::sum);
        }

        for (var row : rows) {
            if (allowedEstilos != null && !allowedEstilos.contains(row.estilo())) continue;
            if (row.liked()) continue;
            String url = row.url();
            if (StringUtils.isBlank(url)) continue;
            Product p = porUrl.get(url);
            if (p == null) continue; // delisted — skip silencioso
            exclude.add(OutfitService.FeedbackModel.keyOf(p));
        }

        Set<String> excludeCategoria = dismissCategorias != null
                ? new HashSet<>(dismissCategorias) : new HashSet<>();

        return new OutfitService.FeedbackModel(exclude, boostLikeCount, excludeCategoria);
    }
}
