package ar.scraper.agent;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * La MISMA normalización + stemming se aplica a la consulta y al texto de los productos, así el
 * plural de un lado matchea el singular del otro.
 */
final class QueryTokenizer {

    /** Palabra normalizada tal como la escribió el usuario, y su raíz (con la que se compara). */
    record Token(String word, String stem) {}

    /**
     * Palabras vacías de la CONSULTA (ya normalizadas: minúscula, sin acentos): función del español
     * y relleno conversacional. Nunca colores, talles, géneros, unidades ni marcas.
     */
    static final Set<String> STOPWORDS = Set.of(
            "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "con", "para", "por",
            "en", "y", "o", "a", "e", "que", "me", "mi", "al", "lo", "se",
            "tenes", "tienen", "tiene", "hay", "algun", "alguno", "alguna", "algunos", "algunas",
            "busco", "buscame", "quiero", "necesito", "mostrame", "dame", "pasame", "quisiera",
            "podes", "podrias", "hola", "favor", "porfa", "cual", "cuales", "algo", "cosa", "cosas",
            "producto", "productos");

    private static final Pattern NUMERO_UNIDAD = Pattern.compile("(\\d) +(?=[a-z])");
    private static final Pattern NO_ALFANUM = Pattern.compile("[^a-z0-9]+");
    private static final String CONSONANTES_ES = "rlndzj";
    private static final int MIN_STEM_LEN = 5;

    private QueryTokenizer() {}

    /** Términos de contenido de una consulta: sin palabras vacías, con raíz. */
    static List<Token> contentTokens(String query) {
        List<Token> out = new ArrayList<>();
        for (String w : words(query, true)) {
            if (!STOPWORDS.contains(w)) out.add(new Token(w, stem(w)));
        }
        return out;
    }

    /**
     * "1 TB" da "1", "tb" y "1tb" (matchea la consulta "1 tb"), y "SA510 SATA" da "sa510", "sata" y
     * "sa510sata" — un código de modelo seguido de una palabra no debe esconder la palabra.
     */
    static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        List<String> sueltas = words(text, false);
        for (String w : sueltas) out.add(stem(w));
        for (String w : words(text, true)) {
            if (!sueltas.contains(w)) out.add(stem(w));
        }
        return out;
    }

    /** Sólo palabras alfabéticas de 5+ letras; nunca toca las que llevan dígitos. */
    static String stem(String word) {
        int n = word.length();
        if (n < MIN_STEM_LEN || !word.endsWith("s")) return word;
        for (int i = 0; i < n; i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') return word;
        }
        if (word.endsWith("es") && CONSONANTES_ES.indexOf(word.charAt(n - 3)) >= 0) {
            return word.substring(0, n - 2);
        }
        return word.substring(0, n - 1);
    }

    private static List<String> words(String text, boolean pegarUnidad) {
        if (text == null || text.isBlank()) return List.of();
        String base = AccentStripper.strip(text.toLowerCase()).trim();
        if (pegarUnidad) base = NUMERO_UNIDAD.matcher(base).replaceAll("$1");
        List<String> out = new ArrayList<>();
        for (String w : NO_ALFANUM.split(base)) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }
}
