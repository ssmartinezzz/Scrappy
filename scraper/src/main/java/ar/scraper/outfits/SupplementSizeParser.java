package ar.scraper.outfits;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Reads the package size out of a supplement's name, so two listings of the same subtype can be
 * compared per unit of measure instead of by sticker price.
 */
final class SupplementSizeParser {

    private SupplementSizeParser() {}

    /** Magnitudes are only comparable within the same family. */
    enum Familia { MASA, VOLUMEN, CONTEO, DESCONOCIDA }

    /**
     * {@code magnitud} is in the family's base unit (grams / millilitres / items), and is 0 exactly
     * when the family is {@code DESCONOCIDA}.
     */
    record Tamano(Familia familia, double magnitud) {
        static final Tamano DESCONOCIDO = new Tamano(Familia.DESCONOCIDA, 0.0);

        boolean conocido() { return familia != Familia.DESCONOCIDA; }
    }

    /** Grams per unit of mass. */
    private static final Map<String, Double> FACTOR_MASA = Map.ofEntries(
            Map.entry("kilogramos", 1000.0), Map.entry("kilogramo", 1000.0),
            Map.entry("kilos", 1000.0), Map.entry("kilo", 1000.0), Map.entry("kg", 1000.0),
            Map.entry("gramos", 1.0), Map.entry("gramo", 1.0),
            Map.entry("grs", 1.0), Map.entry("gr", 1.0), Map.entry("g", 1.0),
            Map.entry("libras", 453.592), Map.entry("libra", 453.592),
            Map.entry("lbs", 453.592), Map.entry("lb", 453.592),
            Map.entry("onzas", 28.3495), Map.entry("onza", 28.3495), Map.entry("oz", 28.3495));

    /**
     * Units that state a DOSE, never a package size — matched so they are consumed and visibly
     * ignored, rather than silently failing to match.
     */
    private static final java.util.Set<String> UNIDADES_DOSIS = java.util.Set.of("mg");

    /** Millilitres per unit of volume. */
    private static final Map<String, Double> FACTOR_VOLUMEN = Map.of(
            "litros", 1000.0, "litro", 1000.0, "lts", 1000.0, "lt", 1000.0, "l", 1000.0,
            "ml", 1.0, "cc", 1.0);

    /** Countable package units — all weight 1, they are already items. */
    private static final java.util.Set<String> UNIDADES_CONTEO = java.util.Set.of(
            "capsulas", "capsula", "caps", "cap",
            "comprimidos", "comprimido", "tabletas", "tableta", "tabs", "tab",
            "softgels", "softgel", "perlas", "perla",
            "sobres", "sobre", "sachets", "sachet",
            "porciones", "porcion", "servicios", "servicio", "servings", "serving",
            "unidades", "unidad", "barras", "barra");

    /**
     * A number followed by a unit. Longest alternatives first for readability; the trailing
     * {@code \b} is what actually prevents "gr" from being read as "g".
     */
    private static final Pattern MEDIDA = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*("
            + "kilogramos|kilogramo|kilos|kilo|kg|gramos|gramo|grs|gr|mg|g"
            + "|libras|libra|lbs|lb|onzas|onza|oz"
            + "|litros|litro|lts|lt|ml|cc|l"
            + "|capsulas|capsula|caps|cap|comprimidos|comprimido|tabletas|tableta|tabs|tab"
            + "|softgels|softgel|perlas|perla|sobres|sobre|sachets|sachet"
            + "|porciones|porcion|servicios|servicio|servings|serving"
            + "|unidades|unidad|barras|barra"
            + ")\\b");

    /**
     * A count beats a mass outright, because in a capsule or sachet product the mass in the title
     * is the dose per unit — "Vitamina C 1000mg x 60 cápsulas" is a 60-item jar, not a 1-gram
     * product.
     */
    static Tamano parse(String nombre) {
        if (StringUtils.isBlank(nombre)) return Tamano.DESCONOCIDO;

        String n = AccentStripper.strip(nombre.toLowerCase());
        double masa = 0.0, volumen = 0.0, conteo = 0.0;

        Matcher m = MEDIDA.matcher(n);
        while (m.find()) {
            Double valor = parseNumero(m.group(1));
            if (valor == null) continue;
            String unidad = m.group(2);
            if (UNIDADES_DOSIS.contains(unidad)) continue;

            Double factorMasa = FACTOR_MASA.get(unidad);
            if (factorMasa != null) {
                masa = Math.max(masa, valor * factorMasa);
                continue;
            }
            Double factorVolumen = FACTOR_VOLUMEN.get(unidad);
            if (factorVolumen != null) {
                volumen = Math.max(volumen, valor * factorVolumen);
                continue;
            }
            if (UNIDADES_CONTEO.contains(unidad)) {
                conteo = Math.max(conteo, valor);
            }
        }

        if (conteo  > 0) return new Tamano(Familia.CONTEO,  conteo);
        if (masa    > 0) return new Tamano(Familia.MASA,    masa);
        if (volumen > 0) return new Tamano(Familia.VOLUMEN, volumen);
        return Tamano.DESCONOCIDO;
    }

    /**
     * Heuristic: a separator followed by exactly three digits is a thousands separator, UNLESS the
     * integer part is a bare "0" ("0.500 kg" is half a kilo, not five hundred).
     */
    private static Double parseNumero(String raw) {
        String s = raw.replace(',', '.');
        int punto = s.indexOf('.');
        if (punto >= 0) {
            String entera = s.substring(0, punto);
            String resto  = s.substring(punto + 1);
            if (resto.length() == 3 && !entera.equals("0")) {
                s = entera + resto;
            }
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
