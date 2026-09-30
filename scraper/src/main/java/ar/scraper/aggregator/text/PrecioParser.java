package ar.scraper.aggregator.text;

import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** . Único parser AR-locale de precio (DD2). */
public final class PrecioParser {

    private PrecioParser() {}

    private static final Pattern NO_NUMERICO = Pattern.compile("[^0-9.,]");
    private static final Pattern PALABRAS_INVALIDAS =
            Pattern.compile("nan|null|undefined|none", Pattern.CASE_INSENSITIVE);

    /**
     * {@code null}/blank → empty contiene {@code nan|null|undefined|none} (case-insensitive) →
     * empty se recorta a {@code [0-9.,]}; si queda vacío → empty 1 coma y ≥1 punto → se descartan
     * los puntos, la coma pasa a punto 1 coma y 0 puntos → la coma pasa a punto 1 punto y 0 comas:
     * parte decimal de EXACTAMENTE 3 dígitos → se descarta el punto (separador de miles — nunca
     * deja otra cantidad de dígitos); parte decimal de 1 o 2 dígitos → se conserva tal cual
     * (decimal real, sin importar el tamaño de la parte entera:
     */
    public static OptionalDouble parse(String raw) {
        if (StringUtils.isBlank(raw)) return OptionalDouble.empty();
        if (PALABRAS_INVALIDAS.matcher(raw).find()) return OptionalDouble.empty();

        String s = NO_NUMERICO.matcher(raw).replaceAll("");
        if (s.isEmpty()) return OptionalDouble.empty();

        long puntos = s.chars().filter(c -> c == '.').count();
        long comas  = s.chars().filter(c -> c == ',').count();

        if (comas == 1 && puntos >= 1) {
            s = s.replace(".", "").replace(",", ".");
        } else if (comas == 1 && puntos == 0) {
            s = s.replace(",", ".");
        } else if (puntos == 1 && comas == 0) {
            int idx = s.indexOf('.');
            String frac = s.substring(idx + 1);
            if (frac.length() == 3) {
                // SIEMPRE deja exactamente 3 dígitos detrás — "199.500", "45.000" — sin importar la
                // parte entera.
                s = s.replace(".", "");
            } else if (frac.length() <= 2) {
                // "39990.00"/"89990.00" son precios de 5 dígitos con un decimal de 2 — un separador
                // de miles real siempre deja 3 dígitos (rama de arriba), nunca 1 o 2.
            } else {
                s = s.replace(".", "");
            }
        } else {
            s = s.replace(".", "").replace(",", "");
        }

        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
        return (v > 0 && v < 100_000_000) ? OptionalDouble.of(v) : OptionalDouble.empty();
    }
}
