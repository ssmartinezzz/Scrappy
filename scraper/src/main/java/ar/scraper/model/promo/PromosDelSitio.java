package ar.scraper.model.promo;

import java.util.List;

/**
 * Site-level promos a scraper read in this run. {@code leido} is the only thing that lets the sweep
 * deactivate a site's promos: a platform with no promo extractor, or a fetch that failed (429,
 * timeout, parse error), stays {@link #NO_LEIDO}, so its stored promos are kept rather than wiped.
 */
public record PromosDelSitio(boolean leido, List<PromoFinanciacion> promos) {

    public static final PromosDelSitio NO_LEIDO = new PromosDelSitio(false, List.of());

    public PromosDelSitio {
        promos = promos == null ? List.of() : List.copyOf(promos);
    }

    /** The read succeeded; an empty list is a real answer ("this site has no promos today"). */
    public static PromosDelSitio leidas(List<PromoFinanciacion> promos) {
        return new PromosDelSitio(true, promos);
    }
}
