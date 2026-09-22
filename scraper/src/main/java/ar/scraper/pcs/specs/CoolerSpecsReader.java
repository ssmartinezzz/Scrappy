package ar.scraper.pcs.specs;

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
                ar.scraper.pcs.TamanioGabinete.DESCONOCIDO, radiadorMm(tokens));
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

    private static boolean esLiderCaseFan(Tokens tokens) {
        String[] arr = tokens.array();
        return arr.length > 0 && tieneAlguno(arr[0], TOKENS_LIDER_CASE_FAN);
    }

    private static boolean esLiquido(Tokens tokens) {
        if (tieneAlguno(tokens, TOKENS_LIQUIDO)) return true;
        return tokens.has("cooler") && tieneAlguno(tokens, TOKENS_RADIADOR);
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
        return 0;
    }
}
