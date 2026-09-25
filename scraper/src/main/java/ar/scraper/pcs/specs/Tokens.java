package ar.scraper.pcs.specs;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.regex.Pattern;

/**
 * A PC part's name, tokenized once. Same convention as {@code
 * CategoryClassifier}: the SPACE is the word boundary (see CLAUDE.md "la
 * taxonomía de categorías"). Splitting on every non-alphanumeric character
 * also solves the "1851 inside B860M" problem for free: "B850M-E" tokenizes
 * to {@code b850m}+{@code e}, and a bare "1851" can never match inside a
 * longer digit run like "SKU21851034" — a token is compared whole, never as
 * a substring.
 */
public final class Tokens {

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    private final String[] tokens;
    private final String padded;
    private final String original;

    private Tokens(String[] tokens, String original) {
        this.tokens = tokens;
        this.padded = " " + String.join(" ", tokens) + " ";
        this.original = original;
    }

    public static Tokens de(String nombre) {
        String n = AccentStripper.strip(nombre.toLowerCase());
        String cleaned = NO_ALFANUMERICO.matcher(n).replaceAll(" ").trim();
        String[] tokens = cleaned.isEmpty() ? new String[0] : cleaned.split(" ");
        return new Tokens(tokens, n);
    }

    public boolean has(String token) {
        for (String t : tokens) if (t.equals(token)) return true;
        return false;
    }

    /** Space-joined, with a leading and trailing space — safe for " word " containment checks. */
    public String padded() {
        return padded;
    }

    public String[] array() {
        return tokens;
    }

    /**
     * Acento-stripeado y lowercased, ANTES del colapso de todo no-alfanumérico
     * a espacios — a diferencia de {@link #array()}/{@link #padded()}, esto
     * conserva puntuación como el punto decimal. Sólo para un llamador que
     * necesita recomponer algo que la tokenización general tira: "1.92TB"
     * tokeniza a {@code "1"}+{@code "92tb"} (el punto es un separador como
     * cualquier otro), y {@link ar.scraper.pcs.specs.AlmacenamientoSpecsReader}
     * necesita el punto de vuelta para leer la capacidad real.
     */
    public String original() {
        return original;
    }
}
