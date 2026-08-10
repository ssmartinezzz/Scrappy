package ar.scraper.aggregator.text;

/**
 * Shared accent-normalization regex chain (ADR-4).
 *
 * <p>This is the byte-identical 6-replacement chain duplicated, before this
 * extraction, across {@code GroupingService.normalizar},
 * {@code JaccardSimilarity}/{@code ProductIdentity}'s tokenizers,
 * {@code BrandExtractor.normalizarAcentos}, and {@code GymratTagger}'s inline
 * chain. Only the identical accent-stripping step is unified here — the
 * divergent stop-word/token filtering that each consumer layers on top stays
 * per-consumer (exploration flagged those as NOT byte-identical across
 * classes).</p>
 *
 * <p>Callers are responsible for lower-casing their input first, matching
 * the pre-extraction call sites (the regex patterns only target lowercase
 * accented characters).</p>
 */
public final class AccentStripper {

    private AccentStripper() {}

    /**
     * Reemplaza cada vocal acentuada por su vocal base y la eñe por ene.
     *
     * <p>Era una cadena de seis {@code String.replaceAll}: seis {@code Pattern}
     * compilados de cero y seis strings intermedios en CADA llamada. No es una
     * función de borde — la usan diez clases, entre ellas el normalizador que
     * corre sobre cada producto de cada scrape y el agrupador que corre sobre
     * el catálogo entero en cada request a {@code /api/grupos}.</p>
     *
     * <p>Ahora es un solo recorrido de caracteres. El reemplazo es una
     * biyección carácter a carácter, así que seis pasadas de regex nunca
     * hicieron falta. Además hay un fast-path: si el texto no trae ninguno de
     * estos caracteres — el caso común — se devuelve la misma instancia sin
     * asignar nada.</p>
     *
     * <p>Deliberadamente NO toca mayúsculas acentuadas ni acentos que el chain
     * original no contemplaba (circunflejo, cedilla, tilde de la ã): los
     * llamadores bajan a minúscula antes, y ampliar la cobertura acá cambiaría
     * la clasificación de productos, no su velocidad.</p>
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

    /** Centinela: {@code '\0'} nunca es destino de un reemplazo. */
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
