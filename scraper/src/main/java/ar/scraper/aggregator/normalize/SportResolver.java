package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SportResolver {

    private static final Pattern NO_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> RUBROS_CON_DEPORTE = Set.of("indumentaria", "deporte");

    /**
     * Sport code for sport-specific merch, or "" when there is no sport. Only clothing and sports
     * equipment have a sport (a blank rubro is indumentaria); the Botines category is football whatever the name says.
     */
    public String resolver(String nombre, String categoria, String rubro) {
        if (StringUtils.isNotBlank(rubro) && !RUBROS_CON_DEPORTE.contains(rubro)) return "";
        if ("Botines".equals(categoria)) return "futbol";

        // Punctuation becomes a space so "(Boca Juniors)," still has its word boundaries.
        String n = " " + NO_ALNUM.matcher(AccentStripper.strip(StringUtils.defaultString(nombre).toLowerCase()))
                .replaceAll(" ").trim() + " ";
        for (Map.Entry<String, String[]> sport : SportTaxonomy.KEYWORDS.entrySet()) {
            for (String kw : sport.getValue()) {
                if (n.contains(" " + kw + " ")) return sport.getKey();
            }
        }
        return "";
    }
}
