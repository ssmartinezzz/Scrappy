package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The mechanism behind the leading-noun sub-categories (V128, V129): rows {@code key|sub|leaders|any|veto} joined by {@code ;}, words
 * by {@code ,} and padded with spaces. The first row of the category's key that fires wins: the name, after the sale prefix, must
 * START with a leader, contain one of {@code any} when present and none of {@code veto}. A name no row claims is {@code ""}.
 * The migrations run the same text in SQL, and their agreement tests fail if the two drift.
 */
final class LeadingNounRules {

    /** The sale words V128 and V129 both skip before reading the leader. */
    static final String PREFIJO_COMUN = "pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini";

    private static final Pattern PUNTUACION = Pattern.compile("[/:,;+()\\[\\]]");
    private static final Pattern BLANCOS = Pattern.compile("\\s+");

    private record Regla(String cat, String sub, String[] lideres, String[] alguna, String[] veto) {}

    private final Map<String, String> claves;
    private final List<Regla> reglas;
    private final Pattern prefijo;

    /** {@code prefijoPropio} is the extra sale-prefix alternatives of this vocabulary (brands, words), or empty. */
    LeadingNounRules(String vocabulario, Map<String, String> claves, String prefijoPropio) {
        this.claves = claves;
        this.reglas = Arrays.stream(vocabulario.split(";", -1)).map(LeadingNounRules::regla).toList();
        this.prefijo = Pattern.compile("^ (?:(?:" + PREFIJO_COMUN + (prefijoPropio.isEmpty() ? "" : "|" + prefijoPropio)
            + "|x?[0-9]+(?:[.,][0-9]+)?)\\s+)+");
    }

    boolean handles(String categoria) {
        return claves.containsKey(categoria);
    }

    /** Call only when {@link #handles}. */
    String resolver(String categoria, String nombre) {
        String clave = claves.get(categoria);
        String n = " " + BLANCOS.matcher(PUNTUACION.matcher(AccentStripper.strip(nombre.toLowerCase())).replaceAll(" ")).replaceAll(" ").trim() + " ";
        String sin = prefijo.matcher(n).replaceFirst(" ");
        for (Regla r : reglas) {
            if (r.cat().equals(clave) && empiezaCon(sin, r.lideres()) && (r.alguna() == null || contiene(n, r.alguna()))
                    && (r.veto() == null || !contiene(n, r.veto()))) {
                return r.sub();
            }
        }
        return "";
    }

    private static boolean empiezaCon(String texto, String[] palabras) {
        return Arrays.stream(palabras).anyMatch(texto::startsWith);
    }

    private static boolean contiene(String texto, String[] palabras) {
        return Arrays.stream(palabras).anyMatch(texto::contains);
    }

    private static Regla regla(String fila) {
        // split_part gives '' for a missing field, so a row may stop after its leaders
        String[] f = Arrays.copyOf(fila.split("\\|", -1), 5);
        return new Regla(f[0], f[1], f[2].split(",", -1), lista(f[3]), lista(f[4]));
    }

    private static String[] lista(String campo) {
        return StringUtils.isEmpty(campo) ? null : campo.split(",", -1);
    }
}
