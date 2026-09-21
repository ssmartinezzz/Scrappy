package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads socket + DDR + form factor off a Motherboard's name. Unchanged from phase 1. */
public final class MotherboardSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Motherboard";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        String chipsetToken = chipsetToken(tokens);
        return new TechSpecs(
                motherboardSocket(tokens, chipsetToken),
                ddr(tokens),
                motherboardFormFactor(tokens, chipsetToken),
                0, 0, "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA);
    }

    private static String explicitSocket(Tokens tokens) {
        if (tokens.has("am5")) return "AM5";
        if (tokens.has("am4")) return "AM4";
        if (tokens.has("am3")) return "AM3";
        if (tokens.has("lga1851") || tokens.has("1851")) return "LGA1851";
        if (tokens.has("lga1700") || tokens.has("1700")) return "LGA1700";
        if (tokens.has("lga1200") || tokens.has("1200") || tokens.has("s1200")) return "LGA1200";
        if (tokens.has("lga1151") || tokens.has("1151") || tokens.has("s1151")) return "LGA1151";
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
            Map.entry("h810", "LGA1851"), Map.entry("b860", "LGA1851"), Map.entry("z890", "LGA1851"),
            Map.entry("h310", "LGA1151"), Map.entry("b360", "LGA1151"), Map.entry("b365", "LGA1151"),
            Map.entry("z390", "LGA1151"), Map.entry("h370", "LGA1151"),
            Map.entry("h410", "LGA1200"), Map.entry("b460", "LGA1200"), Map.entry("z490", "LGA1200"),
            Map.entry("h510", "LGA1200"), Map.entry("b560", "LGA1200"), Map.entry("z590", "LGA1200"));

    private static String chipsetToken(Tokens tokens) {
        for (String t : tokens.array()) {
            if (t.length() < 4 || t.length() > 6) continue;
            if (CHIPSET_SOCKET.containsKey(t.substring(0, 4))) return t;
        }
        return null;
    }

    private static String motherboardSocket(Tokens tokens, String chipsetToken) {
        String explicit = explicitSocket(tokens);
        if (!explicit.isEmpty()) return explicit;
        if (chipsetToken == null) return "";
        return CHIPSET_SOCKET.getOrDefault(chipsetToken.substring(0, 4), "");
    }

    private static final Pattern DDR = Pattern.compile(" ddr([345]) ");

    private static String ddr(Tokens tokens) {
        Matcher m = DDR.matcher(tokens.padded());
        return m.find() ? "DDR" + m.group(1) : "";
    }

    private static String explicitFormFactor(String padded) {
        if (padded.contains(" itx ")) return "ITX";
        if (padded.contains(" matx ") || padded.contains(" m atx ") || padded.contains(" micro atx ")) {
            return "MATX";
        }
        if (padded.contains(" eatx ") || padded.contains(" e atx ")) return "EATX";
        if (padded.contains(" atx ")) return "ATX";
        return "";
    }

    private static String motherboardFormFactor(Tokens tokens, String chipsetToken) {
        String suffix = (chipsetToken != null && chipsetToken.length() > 4) ? chipsetToken.substring(4) : "";
        if (suffix.indexOf('i') >= 0) return "ITX";
        if (suffix.indexOf('m') >= 0) return "MATX"; // covers bare "m" and the "em"/"am" (Extreme+Micro) combo

        String explicit = explicitFormFactor(tokens.padded());
        if (!explicit.isEmpty()) return explicit;
        // A recognized chipset with no size suffix (bare, or an "e"-only
        // Extreme tier) and no explicit form-factor word is a full-size
        // board — the modal default in this catalog.
        if (chipsetToken != null) return "ATX";
        return "";
    }
}
