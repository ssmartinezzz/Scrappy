package ar.scraper.outfits;

import ar.scraper.model.Product;
import org.apache.commons.lang3.StringUtils;

/**
 * The one rule that decides whether a product may appear in a builder or a feed: kids' items and
 * sport-specific merch (club kits, swimwear...) are catalogue-only. Builders and feeds call this
 * instead of repeating their own vetoes.
 */
public final class EligibilityPolicy {

    private EligibilityPolicy() {}

    public static boolean elegible(Product p) {
        if (p == null) return false;
        if ("infantil".equalsIgnoreCase(StringUtils.trimToEmpty(p.genero()))) return false;
        return StringUtils.isBlank(p.deporte());
    }
}
