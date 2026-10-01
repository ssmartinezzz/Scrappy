package ar.scraper.aggregator.text;

/**
 * Shared accent-normalization regex chain (ADR-4). Callers are responsible for lower-casing their
 * input first, matching the pre-extraction call sites (the regex patterns only target lowercase
 * accented characters).
 */
public final class AccentStripper {

    private AccentStripper() {}

    /**
     * No es una función de borde — la usan diez clases, entre ellas el normalizador que corre sobre
     * cada producto de cada scrape y el agrupador que corre sobre el catálogo entero en cada
     * request a {@code /api/grupos}.
     */
    public static String strip(String s) {
        int n = s.length();

        int i = 0;
        while (i < n && sinAcento(s.charAt(i)) == SIN_MAPEO) i++;
        if (i == n) return s;

        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            char base = sinAcento(c);
            sb.append(base == SIN_MAPEO ? c : base);
        }
        return sb.toString();
    }

    /** {@code '\0'} nunca es destino de un reemplazo. */
    private static final char SIN_MAPEO = '\0';

    private static char sinAcento(char c) {
        return switch (c) {
            case 'á', 'à', 'ä' -> 'a';
            case 'é', 'è', 'ë' -> 'e';
            case 'í', 'ì', 'ï' -> 'i';
            case 'ó', 'ò', 'ö' -> 'o';
            case 'ú', 'ù', 'ü' -> 'u';
            case 'ñ'           -> 'n';
            default            -> SIN_MAPEO;
        };
    }
}
