package ar.scraper.pcs.specs;

import ar.scraper.pcs.ClaseDisipador;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoCooler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads supported sockets and cooling technology off a cooler's name (T2d +
 * T4d-2, pc-builder-deep-taxonomy). Everything else abstains, same as phase
 * 1: 483 rows in catalog and no other trivial, measured signal to read off
 * the name yet.
 *
 * <p>A bare "intel"/"amd" word names no socket by itself (D6: both sides of
 * a rule must parse) — only an explicit socket token counts.</p>
 *
 * <p>{@link #tipoCooler}: paste/cleaner/pad products (thermal paste,
 * cleaning cloths) veto FIRST, same shape as the "para gabinete" guard in
 * {@code CategoryClassifier} — "Paño de limpieza Arctic para Pasta térmica"
 * has neither "cooler" nor "disipador" today, but a paste product that
 * happens to mention "para Cooler CPU" must not read as a cooler either. A
 * leading "fan"/"ventilador"/"kit" is a case fan, not a CPU cooler — "Fan
 * Cooler 120mm..." names its diameter, a CPU cooler names its socket or
 * radiator size instead. LIQUIDO needs an explicit AIO/liquid word, or a
 * radiator size (240/280/360/420mm) alongside "cooler". AIRE is the
 * remaining "cooler"/"disipador" names.</p>
 */
public final class CoolerSpecsReader implements LectorDeSpecs {

    private static final String[] TOKENS_LIMPIEZA_O_PASTA = { "pasta", "grasa", "pano", "pad", "thermal" };
    private static final String[] TOKENS_LIDER_CASE_FAN = { "fan", "ventilador", "kit" };
    private static final String[] TOKENS_LIQUIDO = { "water", "aio", "liquid", "liquida", "watercooling" };
    private static final String[] TOKENS_RADIADOR = { "240mm", "280mm", "360mm", "420mm" };
    private static final Pattern RADIADOR = Pattern.compile("^(\\d{3})mm$");
    // T15, pc-builder-homelab: los AIO de ASUS nombran su serie sin la
    // palabra "cooler" ("ASUS TUF LC III 240") y sin el sufijo "mm" en el
    // radiador — "LC"/"RYUO"/"RYUJIN" son series reales de ASUS ROG, nunca
    // otra cosa en este catálogo; "lc" solo no alcanza, exige además un
    // tamaño de radiador reconocido junto a él.
    private static final String[] TOKENS_LIQUIDO_SERIE = { "ryuo", "ryujin" };
    private static final String[] TOKENS_RADIADOR_BARE = { "240", "280", "360", "420" };

    // T16, pc-builder-homelab: profundidad del eje AIRE — hasta acá los 85
    // AIRE del catálogo tenían radiadorMm=0 (eje exclusivo de líquidos) y
    // empataban en TODO, así que el más barato ganaba siempre. Vocabulario
    // MEDIDO contra la dev DB (2026-09-25, 323 filas activas de Cooler); las
    // entradas sin match hoy quedan igual, son series reales de fabricante y
    // el pedido explícito las lista.
    //
    // Frases espaciadas: se buscan sobre `Tokens.padded()` (ya bounded con un
    // espacio a cada lado, mismo mecanismo que KW_RAM/KW_COMIDA en
    // GarmentTaxonomy). Sub-frases con guión ("se-214", "nh-d15"): el propio
    // guión ya es un separador real en el título crudo, así que buscarlas
    // sobre `Tokens.original()` (que preserva guiones, a diferencia de
    // `padded()`) no puede "comerse" una palabra más larga.
    private static final String[] FRASES_DOBLE_TORRE = {
        "dark rock pro", "dark rock elite", "peerless assassin",
        "frozn a620", "frozn a610", "hyper 612", "astria 600",
        "phantom spirit", "dual tower", "doble torre", "dual fan"
    };
    private static final String[] TOKENS_DOBLE_TORRE = { "assassin", "dt621", "ak620" };
    private static final String[] SUBFRASES_DOBLE_TORRE = { "nh-d15" };

    private static final String[] FRASES_TORRE = {
        "hyper 212", "frozn a410", "pure rock", "dark rock 5", "astria 400",
        "v4 alpha", "maestro plus", "corefrozr", "gamma 500", "sigma 540",
        "ice burg", "air frost 4", "rave 3"
    };
    private static final String[] TOKENS_TORRE = { "ak400", "ux500" };
    private static final String[] SUBFRASES_TORRE = { "se-214", "lc-x1210", "lc-ap600" };

    private static final Pattern HEATPIPE_HDP = Pattern.compile("^(\\d{1,2})hdp$");
    private static final Pattern HEATPIPE_H = Pattern.compile("^(\\d{1,2})h$");
    private static final Pattern HEATPIPE_PALABRA = Pattern.compile(" (\\d{1,2}) heatpipes? ");

    @Override
    public String categoria() {
        return "Cooler";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, 0, "",
                ar.scraper.pcs.Gama.DESCONOCIDA, ar.scraper.pcs.Certificacion.NINGUNA,
                0, ar.scraper.pcs.TipoAlmacenamiento.DESCONOCIDO, socketsSoportados(tokens),
                "", 0, 0, 0, false, tipoCooler(tokens), 0,
                ar.scraper.pcs.TamanioGabinete.DESCONOCIDO, radiadorMm(tokens),
                claseDisipador(tokens), heatpipes(tokens));
    }

    private static List<String> socketsSoportados(Tokens tokens) {
        List<String> sockets = new ArrayList<>();
        if (tokens.has("am4")) sockets.add("AM4");
        if (tokens.has("am5")) sockets.add("AM5");
        if (tokens.has("am3")) sockets.add("AM3");
        if (tokens.has("lga1700") || tokens.has("1700")) sockets.add("LGA1700");
        if (tokens.has("lga1851") || tokens.has("1851")) sockets.add("LGA1851");
        if (tokens.has("lga1200") || tokens.has("1200")) sockets.add("LGA1200");
        // "115x" is the compact catalog form and maps ONLY to LGA1151, never LGA1200.
        if (tokens.has("lga1151") || tokens.has("1151") || tokens.has("115x")) sockets.add("LGA1151");
        return List.copyOf(sockets);
    }

    private static TipoCooler tipoCooler(Tokens tokens) {
        if (tieneAlguno(tokens, TOKENS_LIMPIEZA_O_PASTA)) return TipoCooler.DESCONOCIDO;
        if (esLiderCaseFan(tokens)) return TipoCooler.DESCONOCIDO;
        if (esLiquido(tokens)) return TipoCooler.LIQUIDO;
        if (tokens.has("cooler") || tokens.has("disipador")) return TipoCooler.AIRE;
        return TipoCooler.DESCONOCIDO;
    }

    /**
     * Un fan de gabinete, por su sustantivo líder. Cubre las dos grafías, que
     * son el mismo producto con las palabras al revés: {@code "Fan Cooler
     * 120mm..."} y {@code "Cooler Fan 120mm..."}. La fase 7 sólo miraba el
     * PRIMER token, así que ataja la primera y dejaba pasar la segunda —
     * medido sobre la dev DB (2026-09-22): las 6 filas activas que lideran con
     * "cooler fan" son fans de 120/140mm, ninguna es un cooler de CPU, y la
     * más barata ganaba el slot cuando se pedía refrigeración por aire.
     *
     * <p>El par tiene que ser ADYACENTE y al principio: {@code "Cooler CPU
     * Deepcool AK400 con Fan de 120mm"} nombra un fan más adelante y sigue
     * siendo un cooler de CPU. Un {@code "outlet"} líder se pela antes de
     * comparar, misma política que {@code CategoryClassifier.startsWithAny}.</p>
     */
    private static boolean esLiderCaseFan(Tokens tokens) {
        String[] arr = tokens.array();
        int i = (arr.length > 0 && arr[0].equals("outlet")) ? 1 : 0;
        if (arr.length <= i) return false;
        if (tieneAlguno(arr[i], TOKENS_LIDER_CASE_FAN)) return true;
        return arr[i].equals("cooler") && arr.length > i + 1
                && tieneAlguno(arr[i + 1], TOKENS_LIDER_CASE_FAN);
    }

    private static boolean esLiquido(Tokens tokens) {
        if (tieneAlguno(tokens, TOKENS_LIQUIDO)) return true;
        if (tokens.has("cooler") && tieneAlguno(tokens, TOKENS_RADIADOR)) return true;
        if (tieneAlguno(tokens, TOKENS_LIQUIDO_SERIE)) return true;
        return tokens.has("lc") && tieneAlguno(tokens, TOKENS_RADIADOR_BARE);
    }

    private static boolean tieneAlguno(Tokens tokens, String[] candidatos) {
        for (String c : candidatos) if (tokens.has(c)) return true;
        return false;
    }

    private static boolean tieneAlguno(String valor, String[] candidatos) {
        for (String c : candidatos) if (valor.equals(c)) return true;
        return false;
    }

    /**
     * Tamaño del radiador, en mm — el eje que separa dos AIO entre sí (fase
     * 9, D6). Sólo lo declara un cooler que ya leyó como LIQUIDO: el
     * diámetro de un fan de gabinete ("Fan Cooler 120mm") tiene la misma
     * forma y no es un radiador, y {@link #tipoCooler} ya abstiene ahí por
     * el líder.
     *
     * <p>Sólo un token ENTERO de tres dígitos + {@code mm} cuenta. Ruido
     * real que esto tiene que ignorar: "Masterliquid 360 Core" (un 360
     * suelto, sin unidad) y "Th240" (dígitos pegados a letras). Medido: 84
     * de los 171 líquidos del catálogo lo declaran — 240mm×41, 360mm×38,
     * 420mm×2, 280mm×1 (2026-09-22).</p>
     */
    private static int radiadorMm(Tokens tokens) {
        if (tipoCooler(tokens) != TipoCooler.LIQUIDO) return 0;
        for (String t : tokens.array()) {
            Matcher m = RADIADOR.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        // T15: los AIO de ASUS traen el tamaño SIN "mm" ("LC 240", "RYUJIN
        // III 360") — sólo se prueba tras la forma con "mm", y sólo entre
        // productos que ya leyeron LIQUIDO, mismo guard que arriba.
        for (String t : tokens.array()) {
            if (tieneAlguno(t, TOKENS_RADIADOR_BARE)) return Integer.parseInt(t);
        }
        return 0;
    }

    /**
     * Doble torre &gt; torre &gt; desconocida — sólo entre coolers que ya
     * leyeron AIRE (T16). Un líquido no tiene "clase de disipador de aire" y
     * un fan de gabinete/una pasta térmica ya abstuvieron en {@link
     * #tipoCooler} antes de llegar acá.
     */
    private static ClaseDisipador claseDisipador(Tokens tokens) {
        if (tipoCooler(tokens) != TipoCooler.AIRE) return ClaseDisipador.DESCONOCIDA;
        if (contieneFrase(tokens, FRASES_DOBLE_TORRE) || tieneAlguno(tokens, TOKENS_DOBLE_TORRE)
                || contieneSubfrase(tokens, SUBFRASES_DOBLE_TORRE))
            return ClaseDisipador.DOBLE_TORRE;
        if (contieneFrase(tokens, FRASES_TORRE) || tieneAlguno(tokens, TOKENS_TORRE)
                || contieneSubfrase(tokens, SUBFRASES_TORRE))
            return ClaseDisipador.TORRE;
        return ClaseDisipador.DESCONOCIDA;
    }

    /**
     * Cantidad de heatpipes, sólo entre AIRE (T16): {@code "3HDP"}/{@code
     * "4h"} son un solo token (dígitos pegados a la letra, sin separador) y
     * {@code "N heatpipes"} es la forma en dos palabras. 0 = abstención,
     * misma política que {@link #radiadorMm}.
     */
    private static int heatpipes(Tokens tokens) {
        if (tipoCooler(tokens) != TipoCooler.AIRE) return 0;
        for (String t : tokens.array()) {
            Matcher m = HEATPIPE_HDP.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        for (String t : tokens.array()) {
            Matcher m = HEATPIPE_H.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        Matcher m = HEATPIPE_PALABRA.matcher(tokens.padded());
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    /** Frase espaciada, bounded por los espacios que ya trae {@link Tokens#padded()}. */
    private static boolean contieneFrase(Tokens tokens, String[] frases) {
        String p = tokens.padded();
        for (String f : frases) if (p.contains(" " + f + " ")) return true;
        return false;
    }

    /** Sub-frase con guión propio ("se-214"): el guión ya es un separador real, ver el comentario de arriba. */
    private static boolean contieneSubfrase(Tokens tokens, String[] subfrases) {
        String o = " " + tokens.original();
        for (String f : subfrases) if (o.contains(f)) return true;
        return false;
    }
}
