package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What belongs to {@code Mousepad y Mat} and its sub-category. The sub reads the name's alphanumeric tokens ("Mouse-Pad XL" is
 * " mouse pad xl "). V142 repeats every regex below as the same text over the same tokens ({@code V142MousepadYMatAgreementTest}
 * runs both over real names), so a change here needs the same change there.
 */
final class MousepadReglas {

    private MousepadReglas() {}

    static final String CATEGORIA = "Mousepad y Mat";

    /** The pad nouns, once: the combo test removes them, the last sub rule reads them. */
    private static final String PAD = "mouse ?pad|mousepad|pad mouse|alfombrilla (?:para )?mouse|mouse mat";

    /** First rule that matches wins; the order is the priority. */
    static final String[][] SUBS = {
        {"Antifatiga", " (?:antifatiga|anti fatiga) "},
        {"Apoyamuñecas", " (?:apoya ?munecas?|reposa ?munecas?|repose munecas?|descansa ?munecas?|wrist (?:rest|pad)) "},
        {"Mousepad Ergonómico", " ergonomic[oa]s? "},
        {"Mat de Escritorio", " (?:mat (?:de|para) escritorio|desk ?mat|desk pad|deskpad|alfombrilla (?:de|para) escritorio|mat board) "},
        {"Mousepad RGB", " (?:rgb|chroma|led|iluminad[oa]s?) "},
        {"Mousepad XL", " (?:xl|xxl|xxxl|[0-9]xl|extended|extendido|extra grande|gigante) "},
        {"Mousepad", " (?:" + PAD + "|pad) "},
    };

    private record Regla(String sub, Pattern patron) {}

    private static final List<Regla> REGLAS = java.util.Arrays.stream(SUBS).map(f -> new Regla(f[0], Pattern.compile(f[1]))).toList();

    /** A sale word or a plus sign: the name sells several things. */
    private static final Pattern P_PAQUETE = Pattern.compile(" (?:combo|kit|pack|set|bundle|[0-9] en [0-9]) ");
    private static final Pattern P_PAD = Pattern.compile(" (?:" + PAD + ") ");
    private static final Pattern P_OTRO_PERIFERICO = Pattern.compile(" (?:teclado|mouse|auricular|auriculares|headset|monitor|parlante|webcam|microfono) ");

    static boolean handles(String categoria) {
        return CATEGORIA.equals(categoria);
    }

    /** {@code ""} when no rule matches: a name that says nothing about what it is stays empty, never a guess. */
    static String sub(String nombre) {
        String t = GamingReglas.tokens(" " + AccentStripper.strip(nombre.toLowerCase()) + " ");
        for (Regla r : REGLAS) {
            if (r.patron().matcher(t).find()) return r.sub();
        }
        return "";
    }

    /** A desk mat or a wrist rest that opens the name wins before any family: the gaming and clothing words in it are not its noun. */
    static boolean reclamaAntesDeLasFamilias(TextoClasificable texto) {
        return (texto.contiene(GarmentTaxonomy.KW_DESK_MAT) || texto.empiezaCon(GarmentTaxonomy.KW_APOYAMUNECAS_LIDER))
                && !esPaquete(texto);
    }

    private static final Pattern P_PERIFERICO_LIDER = Pattern.compile(" (teclado|mouse|auricular|auriculares|headset) ");

    /**
     * The category of a kit that includes a pad: the first keyboard, mouse or headset in the name. Names glue the parts with "+" and "/"
     * ("TECLADO+MOUSE+MOUSEPAD"), which the space-delimited keywords of the tech chain do not see. {@code ""} when none leads.
     */
    static String categoriaDelPaquete(TextoClasificable texto) {
        String sinPad = P_PAD.matcher(GamingReglas.tokens(texto.normalizado())).replaceAll(" pad ");
        var m = P_PERIFERICO_LIDER.matcher(sinPad);
        if (!m.find()) return "";
        return switch (m.group(1)) {
            case "teclado" -> "Teclado";
            case "mouse" -> "Mouse";
            default -> "Auricular";
        };
    }

    /** A kit of a keyboard, a mouse or a headset that includes a pad is sold as the kit, not as the pad. */
    static boolean esPaquete(TextoClasificable texto) {
        String n = texto.normalizado();
        if (n.indexOf('+') < 0 && !P_PAQUETE.matcher(GamingReglas.tokens(n)).find()) return false;
        String sinPad = P_PAD.matcher(GamingReglas.tokens(n)).replaceAll(" pad ");
        return P_OTRO_PERIFERICO.matcher(sinPad).find();
    }
}
