package ar.scraper.pcs;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads socket / DDR generation / form factor / watts / RAM capacity off a
 * PC part's name. Pure and abstention-first: a field is only ever filled
 * when a rule below actually matched — see the design table this mirrors in
 * {@code odd/tasks/pc-builder-specs.md}.
 *
 * <p>Normalizes to a space-padded, punctuation-stripped lowercase token
 * stream first — same convention as {@code CategoryClassifier}: the SPACE is
 * the word boundary (see CLAUDE.md "la taxonomía de categorías"). Splitting
 * on every non-alphanumeric character also solves the "1851 inside B860M"
 * problem for free: "B850M-E" tokenizes to {@code b850m}+{@code e}, and a
 * bare "1851" can never match inside a longer digit run like
 * "SKU21851034" — a token is compared whole, never as a substring.</p>
 */
public final class TechSpecsParser {

    private TechSpecsParser() {}

    public static TechSpecs parse(String nombre, String categoria) {
        if (nombre == null || nombre.isBlank() || categoria == null) return TechSpecs.EMPTY;

        String[] tokens = tokenize(nombre);

        return switch (categoria) {
            case "CPU" -> new TechSpecs(cpuSocket(tokens), "", "", 0, 0, "");
            case "Motherboard" -> {
                String chipsetToken = chipsetToken(tokens);
                yield new TechSpecs(
                        motherboardSocket(tokens, chipsetToken),
                        ddr(tokens),
                        motherboardFormFactor(tokens, chipsetToken),
                        0, 0, "");
            }
            case "RAM" -> new TechSpecs("", ddr(tokens), "", 0, ramCapacidadGb(tokens), tipoMemoria(tokens));
            case "Fuente" -> new TechSpecs("", "", "", watts(tokens), 0, "");
            case "Gabinete" -> new TechSpecs("", "", gabineteFormFactor(tokens), 0, 0, "");
            default -> TechSpecs.EMPTY; // GPU/Cooler/Monitor/Almacenamiento abstain entirely in phase 1
        };
    }

    // ── normalization ────────────────────────────────────────────────────

    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    private static String[] tokenize(String nombre) {
        String n = AccentStripper.strip(nombre.toLowerCase());
        String cleaned = NO_ALFANUMERICO.matcher(n).replaceAll(" ").trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split(" ");
    }

    private static boolean has(String[] tokens, String token) {
        for (String t : tokens) if (t.equals(token)) return true;
        return false;
    }

    private static String padded(String[] tokens) {
        return " " + String.join(" ", tokens) + " ";
    }

    // ── socket ───────────────────────────────────────────────────────────

    private static String explicitSocket(String[] tokens) {
        if (has(tokens, "am5")) return "AM5";
        if (has(tokens, "am4")) return "AM4";
        if (has(tokens, "lga1851") || has(tokens, "1851")) return "LGA1851";
        if (has(tokens, "lga1700") || has(tokens, "1700")) return "LGA1700";
        return "";
    }

    // Ryzen Nxxx: the model's first digit is what the table keys the socket on.
    private static final Pattern RYZEN_MODEL = Pattern.compile("ryzen \\d+ ([3-9])\\d{3}\\w*");
    // Core i[3579] 1[234]xxx (12th-14th gen desktop model numbers).
    private static final Pattern CORE_MODEL = Pattern.compile("i[3579] (1[234])\\d{2}\\w*");
    // Core Ultra 2xx (the "200 series", Arrow Lake).
    private static final Pattern CORE_ULTRA_MODEL = Pattern.compile("ultra \\d+ 2\\d{2}\\w*");

    private static String cpuSocket(String[] tokens) {
        String explicit = explicitSocket(tokens);
        if (!explicit.isEmpty()) return explicit;

        String padded = padded(tokens);
        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) >= '7' ? "AM5" : "AM4";
        if (CORE_ULTRA_MODEL.matcher(padded).find()) return "LGA1851";
        Matcher core = CORE_MODEL.matcher(padded);
        if (core.find()) return "LGA1700";
        return "";
    }

    // Chipset -> socket. Every key is exactly 4 characters: a motherboard
    // token equals the chipset bare ("z890"), plus a tier letter ("x670e",
    // "Extreme"), plus a size letter ("z890m", "b650i"), or both combined
    // ("b650em", "a620am" — measured against the real catalog, see
    // pc-builder-specs.md: naive 4-or-5-char matching missed every X670E/
    // X870E/B650E board and every "AM"/"EM" compound suffix).
    private static final Map<String, String> CHIPSET_SOCKET = Map.ofEntries(
            Map.entry("a620", "AM5"), Map.entry("b650", "AM5"), Map.entry("b840", "AM5"),
            Map.entry("b850", "AM5"), Map.entry("x670", "AM5"), Map.entry("x870", "AM5"),
            Map.entry("a520", "AM4"), Map.entry("b450", "AM4"), Map.entry("b550", "AM4"),
            Map.entry("x570", "AM4"),
            Map.entry("h610", "LGA1700"), Map.entry("b660", "LGA1700"), Map.entry("b760", "LGA1700"),
            Map.entry("z690", "LGA1700"), Map.entry("z790", "LGA1700"),
            Map.entry("h810", "LGA1851"), Map.entry("b860", "LGA1851"), Map.entry("z890", "LGA1851"));

    private static String chipsetToken(String[] tokens) {
        for (String t : tokens) {
            if (t.length() < 4 || t.length() > 6) continue;
            if (CHIPSET_SOCKET.containsKey(t.substring(0, 4))) return t;
        }
        return null;
    }

    private static String motherboardSocket(String[] tokens, String chipsetToken) {
        String explicit = explicitSocket(tokens);
        if (!explicit.isEmpty()) return explicit;
        if (chipsetToken == null) return "";
        return CHIPSET_SOCKET.getOrDefault(chipsetToken.substring(0, 4), "");
    }

    // ── ddr ──────────────────────────────────────────────────────────────

    private static final Pattern DDR = Pattern.compile(" ddr([345]) ");

    private static String ddr(String[] tokens) {
        Matcher m = DDR.matcher(padded(tokens));
        return m.find() ? "DDR" + m.group(1) : "";
    }

    // ── form factor ──────────────────────────────────────────────────────

    private static String explicitFormFactor(String padded) {
        if (padded.contains(" itx ")) return "ITX";
        if (padded.contains(" matx ") || padded.contains(" m atx ") || padded.contains(" micro atx ")) {
            return "MATX";
        }
        if (padded.contains(" eatx ") || padded.contains(" e atx ")) return "EATX";
        if (padded.contains(" atx ")) return "ATX";
        return "";
    }

    private static String motherboardFormFactor(String[] tokens, String chipsetToken) {
        String suffix = (chipsetToken != null && chipsetToken.length() > 4) ? chipsetToken.substring(4) : "";
        if (suffix.indexOf('i') >= 0) return "ITX";
        if (suffix.indexOf('m') >= 0) return "MATX"; // covers bare "m" and the "em"/"am" (Extreme+Micro) combo

        String explicit = explicitFormFactor(padded(tokens));
        if (!explicit.isEmpty()) return explicit;
        // A recognized chipset with no size suffix (bare, or an "e"-only
        // Extreme tier) and no explicit form-factor word is a full-size
        // board — the modal default in this catalog.
        if (chipsetToken != null) return "ATX";
        return "";
    }

    private static String gabineteFormFactor(String[] tokens) {
        return explicitFormFactor(padded(tokens));
    }

    // ── watts (Fuente only) ──────────────────────────────────────────────

    private static final Pattern WATTS = Pattern.compile("^(\\d{3,4})w$");

    private static int watts(String[] tokens) {
        for (String t : tokens) {
            Matcher m = WATTS.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    // ── RAM capacity + module type ──────────────────────────────────────

    private static final Pattern GB_STANDALONE = Pattern.compile("^(\\d+)gb$");
    private static final Pattern GB_MULTIPLIER = Pattern.compile("^(\\d+)x(\\d+)gb$");

    /**
     * "64GB (2x32GB)" states the total explicitly — take that. Only when no
     * standalone total is stated does the pack multiplier ("2x16GB" alone)
     * get multiplied out.
     */
    private static int ramCapacidadGb(String[] tokens) {
        for (String t : tokens) {
            Matcher m = GB_STANDALONE.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        for (String t : tokens) {
            Matcher m = GB_MULTIPLIER.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1)) * Integer.parseInt(m.group(2));
        }
        return 0;
    }

    private static String tipoMemoria(String[] tokens) {
        return has(tokens, "sodimm") ? "SODIMM" : "DIMM";
    }
}
