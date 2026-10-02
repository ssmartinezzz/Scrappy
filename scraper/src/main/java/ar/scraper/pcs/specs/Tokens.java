package ar.scraper.pcs.specs;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "B850M-E" tokenizes to {@code b850m}+{@code e}, and a bare "1851" can never match inside a longer
 * digit run like "SKU21851034" — a token is compared whole, never as a substring.
 */
public final class Tokens {

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern GB_SUELTO = Pattern.compile("^(\\d+)gb$");

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

    public String socketExplicito() {
        if (has("am5")) return "AM5";
        if (has("am4")) return "AM4";
        if (has("am3")) return "AM3";
        if (has("lga1851") || has("1851")) return "LGA1851";
        if (has("lga1700") || has("1700")) return "LGA1700";
        if (has("lga1200") || has("1200") || has("s1200")) return "LGA1200";
        if (has("lga1151") || has("1151") || has("s1151")) return "LGA1151";
        return "";
    }

    public String formFactorExplicito() {
        if (padded.contains(" itx ")) return "ITX";
        if (padded.contains(" matx ") || padded.contains(" m atx ") || padded.contains(" micro atx ")) {
            return "MATX";
        }
        if (padded.contains(" eatx ") || padded.contains(" e atx ")) return "EATX";
        if (padded.contains(" atx ")) return "ATX";
        return "";
    }

    public OptionalInt gbSuelto() {
        for (String t : tokens) {
            Matcher m = GB_SUELTO.matcher(t);
            if (m.matches()) return OptionalInt.of(Integer.parseInt(m.group(1)));
        }
        return OptionalInt.empty();
    }

    public String padded() {
        return padded;
    }

    public String[] array() {
        return tokens;
    }

    /** Sólo para un llamador que necesita recomponer algo que la tokenización general tira: */
    public String original() {
        return original;
    }
}
