package ar.scraper.aggregator.text;

import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Único parser AR-locale de precio (DD2). Ported verbatim from
 * {@code ml_pipeline.py:354-389}'s {@code safe_price} — esa función es la
 * spec, no una competidora; {@code sp_parse_precio_ar} (V17) es el mismo
 * contrato en SQL, y los tres se prueban contra el mismo fixture
 * ({@code price-parser-cases.tsv}) para que no puedan divergir en silencio.
 *
 * <p>Vive junto a {@link AccentStripper} por la misma razón: una función
 * pura, sin estado, texto→valor, con un solo dueño (CODE-6).</p>
 *
 * <p>Ausencia de opinión (CODE-5): un valor no interpretable devuelve
 * {@link OptionalDouble#empty()}, nunca {@code 0} ni un sentinel — se
 * persiste como {@code NULL} (D1).</p>
 */
public final class PrecioParser {

    private PrecioParser() {}

    private static final Pattern NO_NUMERICO = Pattern.compile("[^0-9.,]");
    private static final Pattern PALABRAS_INVALIDAS =
            Pattern.compile("nan|null|undefined|none", Pattern.CASE_INSENSITIVE);

    /**
     * Parsea un precio en formato argentino.
     *
     * <ol>
     *   <li>{@code null}/blank → empty</li>
     *   <li>contiene {@code nan|null|undefined|none} (case-insensitive) → empty</li>
     *   <li>se recorta a {@code [0-9.,]}; si queda vacío → empty</li>
     *   <li>1 coma y ≥1 punto → se descartan los puntos, la coma pasa a punto</li>
     *   <li>1 coma y 0 puntos → la coma pasa a punto</li>
     *   <li>1 punto y 0 comas: parte decimal de 3 dígitos → se descarta el
     *       punto (separador de miles); parte decimal ≤2 dígitos y parte
     *       entera ≤3 dígitos → se conserva (decimal real); en cualquier
     *       otro caso → se descarta el punto</li>
     *   <li>en cualquier otro caso → se descartan puntos y comas</li>
     *   <li>el valor final debe estar en {@code (0, 100_000_000)} exclusivo,
     *       si no → empty</li>
     * </ol>
     */
    public static OptionalDouble parse(String raw) {
        if (raw == null || raw.isBlank()) return OptionalDouble.empty();
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
            String intPart = s.substring(0, idx);
            String frac = s.substring(idx + 1);
            if (frac.length() == 3) {
                s = s.replace(".", "");
            } else if (frac.length() <= 2 && intPart.length() <= 3) {
                // se conserva tal cual: decimal real
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
