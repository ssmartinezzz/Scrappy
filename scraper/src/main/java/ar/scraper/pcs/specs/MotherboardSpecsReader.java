package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;

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
        return TechSpecs.builder()
                .socket(socket)
                .ddr(ddr(tokens))
                .formFactor(motherboardFormFactor(tokens, chipsetToken))
                .marcaChip(marcaChip(socket))
                .tierChipset(tierChipset(chipsetToken))
                .anioChipset(anioChipset(chipsetToken))
                .wifi(wifi(tokens))
                .build();
    }

    private record Chipset(String socket, int anio) {
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

    private static Chipset chipset(String chipsetToken) {
        return chipsetToken == null ? null : CHIPSETS.get(chipsetToken.substring(0, 4));
    }

    private static String chipsetToken(Tokens tokens) {
        for (String t : tokens.array()) {
            if (t.length() < 4 || t.length() > 6) continue;
            if (CHIPSETS.containsKey(t.substring(0, 4))) return t;
        }
        return null;
    }

    private static String motherboardSocket(Tokens tokens, String chipsetToken) {
        String explicit = tokens.socketExplicito();
        if (!explicit.isEmpty()) return explicit;
        Chipset chipset = chipset(chipsetToken);
        return chipset == null ? "" : chipset.socket();
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

    /** X/Z=1 (top), B=2, A/H=3, sin chipset legible=0 (abstención). B840 is A620 silicon (PCIe 3.0, no CPU OC). */
    private static int tierChipset(String chipsetToken) {
        if (chipsetToken == null) return 0;
        if (chipsetToken.startsWith("b840")) return 3;
        return switch (chipsetToken.charAt(0)) {
            case 'x', 'z' -> 1;
            case 'b' -> 2;
            case 'a', 'h' -> 3;
            default -> 0;
        };
    }

    private static int anioChipset(String chipsetToken) {
        Chipset chipset = chipset(chipsetToken);
        return chipset == null ? 0 : chipset.anio();
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
