package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.SocketDeNombre;

import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sub-category of hardware, which is its platform (socket, DDR generation, chip maker...) read from the name's
 * alphanumeric tokens. Split from {@link SubcategoryResolver}, which keeps the apparel and sport subs; a new
 * hardware category is one entry in {@link #POR_CATEGORIA}. {@code ""} when the name does not say, never a guess.
 */
final class HardwareSubcategoryResolver {

    /** categoria -> (tokens, space-padded tokens) -> sub. */
    private static final Map<String, BiFunction<String[], String, String>> POR_CATEGORIA = Map.of(
        "CPU", (t, padded) -> socketConSub(SocketDeNombre.cpu(t, padded)),
        "Motherboard", (t, padded) -> socketConSub(SocketDeNombre.motherboard(t, SocketDeNombre.chipsetToken(t))),
        "RAM", HardwareSubcategoryResolver::ram,
        "GPU", (t, padded) -> gpu(t),
        "Auricular", HardwareSubcategoryResolver::auricular,
        "PC", (t, padded) -> fabricanteGpu(t, false).isEmpty() ? "oficina" : "gamer" // a dedicated GPU makes it gamer
    );

    /** Platforms with a sub; any other socket (AM3, LGA2011...) is outside the taxonomy and stays empty. */
    private static final Set<String> SOCKETS_CON_SUB = Set.of("am4", "am5", "lga1700", "lga1851", "lga1200", "lga1151");

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern RX_MODELO = Pattern.compile("rx\\d{3,4}\\w*");
    private static final Pattern GT_MODELO = Pattern.compile("gt\\d{3,4}\\w*");
    private static final Pattern DIGITOS_3_4 = Pattern.compile("\\d{3,4}\\w*");
    private static final Pattern ARC_MODELO = Pattern.compile("[ab]\\d{2,3}\\w*");
    private static final Pattern RADEON_R = Pattern.compile("r[579]");
    private static final Pattern GT_AL_REVES = Pattern.compile("\\d{3,4}gt");

    // V103 uses the same regex text on the same padded tokens; V103AgreementTest fails if they drift.
    private static final Pattern VELOCIDAD_RAM = Pattern.compile("([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ");
    private static final Pattern AURICULAR_GAMER = Pattern.compile(
        " (?:gamers?|gaming|quantum|hyperx|virtuoso|redragon|astro|razer|arctis|fantech|cougar|aula|gxt|rog|tuf"
        + "|afterglow|pdp|vsg|raptor|surround|rgb|gx|7 1|g pro|hs g[0-9]+[a-z]*) ");
    private static final Pattern HS_GENIUS = Pattern.compile(" hs [0-9]+[a-z]* ");
    private static final Pattern AURICULAR_EN_OREJA = Pattern.compile(
        " (?:earbuds?|buds|tws|true wireless) ");
    private static final Pattern HS_CORSAIR = Pattern.compile(" hs[0-9]+[a-z]* ");
    private static final Pattern G_LOGITECH = Pattern.compile(" g[0-9]{3}[a-z]* ");
    private static final Pattern H_LOGITECH = Pattern.compile(" h[0-9]{3}[a-z]* ");
    private static final Pattern CH_COOLER_MASTER = Pattern.compile(" ch[0-9]+[a-z]* ");

    boolean handles(String categoria) {
        return POR_CATEGORIA.containsKey(categoria);
    }

    /**
     * Tokens, not the padded name: the platform words arrive glued to punctuation ("AM5,", "(am5)", "S.1700"). V103
     * normalizes the same way. Call only when {@link #handles}.
     */
    String resolver(String categoria, String nombre) {
        String[] t = NO_ALFANUMERICO.split(normalizarAcentos(nombre).trim());
        String padded = " " + String.join(" ", t) + " ";
        return POR_CATEGORIA.get(categoria).apply(t, padded);
    }

    private static String socketConSub(String socket) {
        String sub = socket.toLowerCase();
        return SOCKETS_CON_SUB.contains(sub) ? sub : "";
    }

    private static String ram(String[] t, String padded) {
        if (SocketDeNombre.tiene(t, "sodimm") || padded.contains(" so dimm ")
                || SocketDeNombre.tiene(t, "notebook") || SocketDeNombre.tiene(t, "laptop")) return "notebook";
        if (SocketDeNombre.tiene(t, "ddr5") || SocketDeNombre.tiene(t, "dddr5")) return "ddr5";
        if (SocketDeNombre.tiene(t, "ddr4") || SocketDeNombre.tiene(t, "dddr4")) return "ddr4";
        if (SocketDeNombre.tiene(t, "ddr3")) return "ddr3";
        if (SocketDeNombre.tiene(t, "ddr2") || SocketDeNombre.tiene(t, "ddr")) return ""; // an old or unnamed generation: no speed guess
        return ddrPorVelocidad(padded);
    }

    /** No DDR token: the speed names the generation. DDR3 stops at 1866, DDR4 spans 2133-3600, DDR5 starts at 4800. */
    private static String ddrPorVelocidad(String padded) {
        Matcher m = VELOCIDAD_RAM.matcher(padded);
        if (!m.find()) return "";
        int mhz = Integer.parseInt(m.group(1));
        if (mhz >= 4800) return "ddr5";
        if (mhz >= 2133 && mhz <= 3600) return "ddr4";
        if (mhz <= 1866) return "ddr3";
        return "";
    }

    private static String gpu(String[] t) {
        return fabricanteGpu(t, true);
    }

    /**
     * Chip maker. A bare "radeon" is an integrated GPU in a CPU or PC name ("Radeon Vega 11"), so it only counts
     * when {@code radeonSolo} (the GPU category itself); "RX 9060" always does. The same goes for "Nvidia 1030GT"
     * and "Radeon R5 230" written without a series word: a PC name can say "Nvidia" or "R5 5600G" for other reasons. A lone "rx" or "arc" is a cooler or a manga.
     */
    private static String fabricanteGpu(String[] t, boolean radeonSolo) {
        boolean nvidia = false;
        boolean amd = false;
        boolean intel = false;
        for (int i = 0; i < t.length; i++) {
            String s = t[i];
            String sig = i + 1 < t.length ? t[i + 1] : "";
            if ((radeonSolo && (s.equals("nvidia") || GT_AL_REVES.matcher(s).matches()))
                    || s.equals("geforce") || s.equals("quadro") || s.startsWith("rtx") || s.startsWith("gtx")
                    || GT_MODELO.matcher(s).matches()
                    || (s.equals("gt") && DIGITOS_3_4.matcher(sig).matches())) nvidia = true;
            if ((radeonSolo && (s.equals("radeon") || (RADEON_R.matcher(s).matches() && DIGITOS_3_4.matcher(sig).matches())))
                    || RX_MODELO.matcher(s).matches()
                    || (s.equals("rx") && DIGITOS_3_4.matcher(sig).matches())) amd = true;
            String modelo = sig.equals("pro") && i + 2 < t.length ? t[i + 2] : sig; // "Arc Pro B60"
            if (s.equals("arc") && ARC_MODELO.matcher(modelo).matches()) intel = true;
        }
        return nvidia ? "nvidia" : amd ? "amd" : intel ? "intel" : "";
    }

    /**
     * In-ear first when the name says buds / TWS / true wireless (they are wireless, but in-ear is what they are),
     * then wireless > gamer > office > the generic in-ear words. Typos seen in the data count: wireles, bluetooh.
     */
    private static String auricular(String[] t, String padded) {
        if (AURICULAR_EN_OREJA.matcher(padded).find()) return "in-ear";
        for (String s : t) {
            if (s.startsWith("inalambric") || s.equals("wireless") || s.equals("wireles")
                    || s.equals("bluetooth") || s.equals("bluetooh")) return "inalámbrico";
        }
        if (esAuricularGamer(padded)) return "gamer";
        if (esAuricularOficina(padded)) return "oficina";
        if (padded.contains(" in ear ") || SocketDeNombre.tiene(t, "earphone") || SocketDeNombre.tiene(t, "earphones")
                || SocketDeNombre.tiene(t, "intraural")) return "in-ear";
        return "";
    }

    /** Call-centre lines: Logitech H###/Zone, Genius HS-### (the HS-G### ones are gaming and matched before). */
    private static boolean esAuricularOficina(String padded) {
        return padded.contains(" zone ")
                || padded.contains(" logitech ") && H_LOGITECH.matcher(padded).find()
                || padded.contains(" genius ") && HS_GENIUS.matcher(padded).find();
    }

    private static boolean esAuricularGamer(String padded) {
        return AURICULAR_GAMER.matcher(padded).find()
                || padded.contains(" corsair ") && HS_CORSAIR.matcher(padded).find()
                || padded.contains(" logitech ") && G_LOGITECH.matcher(padded).find()
                || padded.contains(" cooler master ") && CH_COOLER_MASTER.matcher(padded).find();
    }

    private static String normalizarAcentos(String s) {
        return AccentStripper.strip(s.toLowerCase());
    }
}
