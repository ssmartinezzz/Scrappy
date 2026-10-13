package ar.scraper.aggregator.grouping;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Blocking key of a product: its category family, the first word of {@code categoria} lower-cased,
 * accent-stripped and reduced to letters ("Zapatilla Running" and "Zapatilla" share "zapatilla").
 * Model matching inside a family is {@link JaccardSimilarity}'s job.
 */
@Component
public class ProductIdentity {

    private static final Pattern NO_LETRA = Pattern.compile("[^a-z]");

    String calcularIdentidad(Product p) {
        String cat = StringUtils.trimToEmpty(p.categoria());
        if (cat.isEmpty()) return "";
        String primera = StringUtils.substringBefore(cat, " ");
        return NO_LETRA.matcher(AccentStripper.strip(primera.toLowerCase())).replaceAll("");
    }
}
