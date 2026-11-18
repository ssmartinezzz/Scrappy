package ar.scraper.pcs.specs;

import ar.scraper.model.SocketDeNombre;
import ar.scraper.pcs.TechSpecs;

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

    private static String chipsetToken(Tokens tokens) {
        return SocketDeNombre.chipsetToken(tokens.array());
    }

    private static String motherboardSocket(Tokens tokens, String chipsetToken) {
        return SocketDeNombre.motherboard(tokens.array(), chipsetToken);
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
        SocketDeNombre.Chipset chipset = SocketDeNombre.chipset(chipsetToken);
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
