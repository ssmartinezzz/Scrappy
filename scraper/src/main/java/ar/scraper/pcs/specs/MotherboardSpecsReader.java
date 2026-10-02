package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MotherboardSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Motherboard";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        String chipsetToken = chipsetToken(tokens);
        String socket = motherboardSocket(tokens, chipsetToken);
        return new TechSpecs(
                socket,
                ddr(tokens),
                motherboardFormFactor(tokens, chipsetToken),
                0, 0, "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(),
                marcaChip(socket), 0, tierChipset(chipsetToken), 0, wifi(tokens));
    }

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
        String explicit = tokens.socketExplicito();
        if (!explicit.isEmpty()) return explicit;
        if (chipsetToken == null) return "";
        return CHIPSET_SOCKET.getOrDefault(chipsetToken.substring(0, 4), "");
    }

    private static final Pattern DDR = Pattern.compile(" ddr([345]) ");

    private static String ddr(Tokens tokens) {
        Matcher m = DDR.matcher(tokens.padded());
        return m.find() ? "DDR" + m.group(1) : "";
    }

    private static String motherboardFormFactor(Tokens tokens, String chipsetToken) {
        String suffix = (chipsetToken != null && chipsetToken.length() > 4) ? chipsetToken.substring(4) : "";
        if (suffix.indexOf('i') >= 0) return "ITX";
        if (suffix.indexOf('m') >= 0) return "MATX";

        String explicit = tokens.formFactorExplicito();
        if (!explicit.isEmpty()) return explicit;
        // A recognized chipset with no size suffix (bare, or an "e"-only Extreme tier) and no
        // explicit form-factor word is a full-size board — the modal default in this catalog.
        if (chipsetToken != null) return "ATX";
        return "";
    }

    private static String marcaChip(String socket) {
        if (socket.startsWith("AM")) return "AMD";
        if (socket.startsWith("LGA")) return "INTEL";
        return "";
    }

    /** X/Z=1 (top), B=2, A/H=3, sin chipset legible=0 (abstención). */
    private static int tierChipset(String chipsetToken) {
        if (chipsetToken == null) return 0;
        return switch (chipsetToken.charAt(0)) {
            case 'x', 'z' -> 1;
            case 'b' -> 2;
            case 'a', 'h' -> 3;
            default -> 0;
        };
    }

    private static boolean wifi(Tokens tokens) {
        String[] arr = tokens.array();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals("wifi") || arr[i].startsWith("wifi")) return true;
            if (arr[i].equals("wi") && i + 1 < arr.length && arr[i + 1].equals("fi")) return true;
        }
        return false;
    }
}
