package ar.scraper.pcs.specs;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CpuSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "CPU";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return TechSpecs.builder()
                .socket(cpuSocket(tokens))
                .gama(gama(tokens))
                .marcaChip(marcaChip(tokens))
                .generacion(generacion(tokens))
                .nivel(nivel(tokens))
                .build();
    }

    private static final Pattern RYZEN_MODEL = Pattern.compile("ryzen \\d+ ([3-9])\\d{3}\\w*");
    private static final Pattern CORE_MODEL = Pattern.compile("i[3579] (1[234])\\d{2}\\w*");
    private static final Pattern CORE_MODEL_1200 = Pattern.compile("i[3579] (1[01])\\d{2}\\w*");
    private static final Pattern CORE_MODEL_1151 = Pattern.compile("i[3579] ([89])\\d{3}\\w*");
    private static final Pattern CORE_ULTRA_MODEL = Pattern.compile("ultra \\d+ 2\\d{2}\\w*");

    /**
     * Medido contra la dev DB: el único CPU de escritorio real sin socket legible es un
     * {@code Athlon 3000G}, y sin esto perdía el veto de {@code ReglaSocket} contra una mother AM5.
     */
    private static final Set<String> ATHLON_DESKTOP_G =
            Set.of("3000g", "200ge", "220ge", "240ge", "300ge", "320ge");

    private static String cpuSocket(Tokens tokens) {
        String explicit = tokens.socketExplicito();
        if (!explicit.isEmpty()) return explicit;

        String padded = tokens.padded();
        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) >= '7' ? "AM5" : "AM4";
        if (CORE_ULTRA_MODEL.matcher(padded).find()) return "LGA1851";
        if (CORE_MODEL.matcher(padded).find()) return "LGA1700";
        if (CORE_MODEL_1200.matcher(padded).find()) return "LGA1200";
        if (CORE_MODEL_1151.matcher(padded).find()) return "LGA1151";
        String athlon = athlonDesktopSocket(tokens);
        if (!athlon.isEmpty()) return athlon;
        return xeonE5ServerSocket(tokens);
    }

    /**
     * Sólo Athlon de escritorio — nunca Xeon (servidor, fuera de esta escala, ver {@link #gama}) ni
     * Athlon móvil (Silver/Gold "U", sin evidencia medida de socket): abstiene para los dos, no
     * inventa.
     */
    private static String athlonDesktopSocket(Tokens tokens) {
        if (!tokens.has("athlon")) return "";
        for (String t : tokens.array()) {
            if (ATHLON_DESKTOP_G.contains(t)) return "AM4";
        }
        return "";
    }

    private static final Pattern XEON_E5_V34 = Pattern.compile(" e5 \\d{3,4} v[34] ");

    private static String xeonE5ServerSocket(Tokens tokens) {
        if (!tokens.has("xeon")) return "";
        if (XEON_E5_V34.matcher(tokens.padded()).find()) return "LGA2011-3";
        return "";
    }

    /** Package-visible (not private): */
    static Gama gama(Tokens tokens) {
        for (String t : tokens.array()) {
            if (t.endsWith("x3d")) return Gama.ALTA; // el sufijo gana solo, sin importar la familia
        }

        String padded = tokens.padded();
        if (tokens.has("i9") || tokens.has("i7")
                || padded.contains(" ryzen 9 ") || padded.contains(" ryzen 7 ")
                || padded.contains(" ultra 9 ") || padded.contains(" ultra 7 ")) {
            return Gama.ALTA;
        }
        if (tokens.has("i5") || padded.contains(" ryzen 5 ") || padded.contains(" ultra 5 ")) {
            return Gama.MEDIA;
        }
        if (tokens.has("i3") || padded.contains(" ryzen 3 ") || padded.contains(" ultra 3 ")
                || tokens.has("athlon") || tokens.has("celeron") || tokens.has("pentium")) {
            return Gama.BAJA;
        }
        // Xeon es de servidor: no mapea a esta escala de escritorio, queda DESCONOCIDA salvo
        // evidencia medida que diga lo contrario.
        return Gama.DESCONOCIDA;
    }

    /** Package-visible (not private): */
    static String marcaChip(Tokens tokens) {
        if (tokens.has("intel") || tokens.has("i3") || tokens.has("i5") || tokens.has("i7") || tokens.has("i9")
                || tokens.has("ultra") || tokens.has("celeron") || tokens.has("pentium")) {
            return "INTEL";
        }
        if (tokens.has("amd") || tokens.has("ryzen") || tokens.has("athlon")) return "AMD";
        return "";
    }

    /**
     * Athlon/Celeron/Pentium tienen gama BAJA pero no juegan en esta escala: abstienen, y el eje
     * los manda al final.
     */
    /** Package-visible (not private): */
    static int nivel(Tokens tokens) {
        String padded = tokens.padded();
        if (tokens.has("i9") || padded.contains(" ryzen 9 ") || padded.contains(" ultra 9 ")) return 9;
        if (tokens.has("i7") || padded.contains(" ryzen 7 ") || padded.contains(" ultra 7 ")) return 7;
        if (tokens.has("i5") || padded.contains(" ryzen 5 ") || padded.contains(" ultra 5 ")) return 5;
        if (tokens.has("i3") || padded.contains(" ryzen 3 ") || padded.contains(" ultra 3 ")) return 3;
        return 0;
    }

    private static int generacion(Tokens tokens) {
        String padded = tokens.padded();

        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) - '0';

        if (CORE_ULTRA_MODEL.matcher(padded).find()) return 15;

        Matcher core = CORE_MODEL.matcher(padded);
        if (core.find()) return Integer.parseInt(core.group(1));

        Matcher core1200 = CORE_MODEL_1200.matcher(padded);
        if (core1200.find()) return Integer.parseInt(core1200.group(1));

        Matcher core1151 = CORE_MODEL_1151.matcher(padded);
        if (core1151.find()) return Integer.parseInt(core1151.group(1));

        return 0;
    }
}
