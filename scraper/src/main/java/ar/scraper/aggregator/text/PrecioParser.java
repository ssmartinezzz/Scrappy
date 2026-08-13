package ar.scraper.aggregator.text;

import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Único parser AR-locale de precio (DD2). El contrato arrancó como un port
 * verbatim de {@code ml_pipeline.py:354-389}'s {@code safe_price}, pero la
 * regla del punto único (ver {@link #parse}, ítem 6) tenía un bug real de
 * producción heredado del incumbente: exigía además que la parte ENTERA
 * fuera chica (≤3 dígitos) para tratar el punto como decimal, así que
 * {@code "$39990.00"} — 100% legítimo, un precio de 5 dígitos con dos
 * decimales — se leía como separador de miles y se convertía en
 * {@code 3999000.0}. Un separador de miles real SIEMPRE deja exactamente 3
 * dígitos detrás del punto (esa es la rama de arriba, ítem 6a); una parte
 * decimal de 1 o 2 dígitos ya es prueba suficiente de que es un decimal
 * real, sin importar cuán grande sea la parte entera. Corregido acá y en
 * {@code sp_parse_precio_ar} (V17) y {@code safe_price} — fidelidad al
 * incumbente perdió contra corrección, la corrección viaja a los tres.
 *
 * <p>{@code sp_parse_precio_ar} (V17) es el mismo contrato en SQL, y los tres
 * se prueban contra el mismo fixture ({@code price-parser-cases.tsv}) para
 * que no puedan divergir en silencio — ni siquiera divergir de forma
 * consistente hacia una respuesta incorrecta, que es exactamente lo que pasó
 * acá: el fixture se había construido copiando el comportamiento observado
 * del incumbente en vez de derivarlo del dominio.</p>
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
     *   <li>1 punto y 0 comas: parte decimal de EXACTAMENTE 3 dígitos → se
     *       descarta el punto (separador de miles — nunca deja otra
     *       cantidad de dígitos); parte decimal de 1 o 2 dígitos → se
     *       conserva tal cual (decimal real, sin importar el tamaño de la
     *       parte entera: {@code "39990.00"} es un precio de 5 dígitos con
     *       2 decimales, no miles); en cualquier otro caso (4+ dígitos) → se
     *       descarta el punto</li>
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
            String frac = s.substring(idx + 1);
            if (frac.length() == 3) {
                // separador de miles: SIEMPRE deja exactamente 3 dígitos
                // detrás — "199.500", "45.000" — sin importar la parte entera.
                s = s.replace(".", "");
            } else if (frac.length() <= 2) {
                // decimal real. NO exigir además que la parte entera sea
                // chica: "39990.00"/"89990.00" son precios de 5 dígitos con
                // un decimal de 2 — un separador de miles real siempre deja
                // 3 dígitos (rama de arriba), nunca 1 o 2. Esa condición
                // extra fue el bug: convertía $39990.00 en 3999000.
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
