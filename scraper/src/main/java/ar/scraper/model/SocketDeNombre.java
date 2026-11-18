package ar.scraper.model;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a CPU or motherboard socket off a product name already split into alphanumeric tokens.
 * Lives in {@code model} because both {@code pcs} (spec readers) and {@code aggregator}
 * ({@code SubcategoryResolver}) need it, and either depending on the other would close a package cycle.
 */
public final class SocketDeNombre {

    private SocketDeNombre() {}

    public record Chipset(String socket, int anio) {
    }

    private static Map.Entry<String, Chipset> entrada(String prefijo, String socket, int anio) {
        return Map.entry(prefijo, new Chipset(socket, anio));
    }

    private static final Map<String, Chipset> CHIPSETS = Map.ofEntries(
            entrada("a620", "AM5", 2023),
            entrada("b650", "AM5", 2022),
            entrada("b840", "AM5", 2025),
            entrada("b850", "AM5", 2025),
            entrada("x670", "AM5", 2022),
            entrada("x870", "AM5", 2024),
            entrada("a520", "AM4", 2020),
            entrada("b450", "AM4", 2018),
            entrada("b550", "AM4", 2020),
            entrada("x570", "AM4", 2019),
            entrada("h610", "LGA1700", 2022),
            entrada("b660", "LGA1700", 2022),
            entrada("b760", "LGA1700", 2023),
            entrada("z690", "LGA1700", 2021),
            entrada("z790", "LGA1700", 2022),
            entrada("h810", "LGA1851", 2025),
            entrada("b860", "LGA1851", 2025),
            entrada("z890", "LGA1851", 2024),
            entrada("h110", "LGA1151", 2015),
            entrada("b150", "LGA1151", 2015),
            entrada("h170", "LGA1151", 2015),
            entrada("z170", "LGA1151", 2015),
            entrada("b250", "LGA1151", 2017),
            entrada("h270", "LGA1151", 2017),
            entrada("z270", "LGA1151", 2017),
            entrada("h310", "LGA1151", 2018),
            entrada("b360", "LGA1151", 2018),
            entrada("b365", "LGA1151", 2018),
            entrada("z390", "LGA1151", 2018),
            entrada("h370", "LGA1151", 2018),
            entrada("h410", "LGA1200", 2020),
            entrada("b460", "LGA1200", 2020),
            entrada("z490", "LGA1200", 2020),
            entrada("h510", "LGA1200", 2021),
            entrada("b560", "LGA1200", 2021),
            entrada("z590", "LGA1200", 2021));

    public static final Pattern RYZEN_MODEL = Pattern.compile("ryzen \\d+ ([3-9])\\d{3}\\w*");
    public static final Pattern CORE_MODEL = Pattern.compile("i[3579] (1[234])\\d{2}\\w*");
    public static final Pattern CORE_MODEL_1200 = Pattern.compile("i[3579] (1[01])\\d{2}\\w*");
    public static final Pattern CORE_MODEL_1151 = Pattern.compile("i[3579] ([89])\\d{3}\\w*");
    /** Desktop 6th..9th gen only matters for the socket; the pcs generation score keeps reading {@link #CORE_MODEL_1151}. */
    private static final Pattern CORE_MODEL_1151_SOCKET = Pattern.compile("i[3579] [6-9]\\d{3}\\w*");
    private static final Pattern PENTIUM_CELERON_1151 = Pattern.compile("g(?:4\\d{3}|5[0-4]\\d{2})[a-z]*");
    private static final Pattern PENTIUM_CELERON_1200 = Pattern.compile("g(?:59|64)\\d{2}[a-z]*");
    private static final Pattern PENTIUM_CELERON_1700 = Pattern.compile("g74\\d{2}[a-z]*");
    public static final Pattern CORE_ULTRA_MODEL = Pattern.compile("ultra \\d+ 2\\d{2}\\w*");

    /**
     * Measured against the dev DB: the only real desktop CPU with no readable socket is an
     * {@code Athlon 3000G}, and without this it lost the {@code ReglaSocket} veto against an AM5 board.
     */
    private static final Set<String> ATHLON_DESKTOP_G =
            Set.of("3000g", "200ge", "220ge", "240ge", "300ge", "320ge");

    private static final Pattern XEON_E5_V34 = Pattern.compile(" e5 \\d{3,4} v[34] ");

    /** Newest first. The bare number, "lga1700" and the glued "s1700" ("S.1700" tokenizes to "s 1700") all name it. */
    private static final String[] SOCKETS_INTEL = {"1851", "1700", "1200", "1151"};

    public static boolean tiene(String[] tokens, String token) {
        for (String t : tokens) if (t.equals(token)) return true;
        return false;
    }

    public static String explicito(String[] tokens) {
        if (tiene(tokens, "am5")) return "AM5";
        if (tiene(tokens, "am4")) return "AM4";
        if (tiene(tokens, "am3")) return "AM3";
        for (String socket : SOCKETS_INTEL) {
            if (tiene(tokens, socket) || tiene(tokens, "lga" + socket) || tiene(tokens, "s" + socket)) {
                return "LGA" + socket;
            }
        }
        return "";
    }

    /** {@code padded} is the tokens joined by single spaces and wrapped in one space each side. */
    public static String cpu(String[] tokens, String padded) {
        String explicit = explicito(tokens);
        if (!explicit.isEmpty()) return explicit;

        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) >= '7' ? "AM5" : "AM4";
        if (CORE_ULTRA_MODEL.matcher(padded).find()) return "LGA1851";
        if (CORE_MODEL.matcher(padded).find()) return "LGA1700";
        if (CORE_MODEL_1200.matcher(padded).find()) return "LGA1200";
        if (CORE_MODEL_1151_SOCKET.matcher(padded).find()) return "LGA1151";
        String intelBasico = pentiumCeleron(tokens);
        if (!intelBasico.isEmpty()) return intelBasico;
        String athlon = athlonDesktop(tokens);
        if (!athlon.isEmpty()) return athlon;
        return xeonE5Server(tokens, padded);
    }

    /** Pentium / Celeron "Gxxxx": the model number says the socket (G4xxx, G5400 -> 1151; G59xx, G64xx -> 1200). */
    private static String pentiumCeleron(String[] tokens) {
        if (!tiene(tokens, "pentium") && !tiene(tokens, "celeron")) return "";
        for (String t : tokens) {
            if (PENTIUM_CELERON_1151.matcher(t).matches()) return "LGA1151";
            if (PENTIUM_CELERON_1200.matcher(t).matches()) return "LGA1200";
            if (PENTIUM_CELERON_1700.matcher(t).matches()) return "LGA1700";
        }
        return "";
    }

    /**
     * Only desktop Athlon — never Xeon (server, out of scale) nor mobile Athlon (Silver/Gold "U", no
     * measured socket evidence): both abstain.
     */
    private static String athlonDesktop(String[] tokens) {
        if (!tiene(tokens, "athlon")) return "";
        for (String t : tokens) {
            if (ATHLON_DESKTOP_G.contains(t)) return "AM4";
        }
        return "";
    }

    private static String xeonE5Server(String[] tokens, String padded) {
        if (!tiene(tokens, "xeon")) return "";
        if (XEON_E5_V34.matcher(padded).find()) return "LGA2011-3";
        return "";
    }

    public static Chipset chipset(String chipsetToken) {
        return chipsetToken == null ? null : CHIPSETS.get(chipsetToken.substring(0, 4));
    }

    public static String chipsetToken(String[] tokens) {
        for (String t : tokens) {
            if (t.length() < 4 || t.length() > 6) continue;
            if (CHIPSETS.containsKey(t.substring(0, 4))) return t;
        }
        return null;
    }

    public static String motherboard(String[] tokens, String chipsetToken) {
        String explicit = explicito(tokens);
        if (!explicit.isEmpty()) return explicit;
        Chipset chipset = chipset(chipsetToken);
        return chipset == null ? "" : chipset.socket();
    }
}
