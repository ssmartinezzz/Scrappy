package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads socket + power tier off a CPU's name. Socket logic is moved
 * unchanged from phase 1's {@code TechSpecsParser}.
 */
public final class CpuSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "CPU";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs(cpuSocket(tokens), "", "", 0, 0, "", gama(tokens), Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(),
                marcaChip(tokens), generacion(tokens), 0, 0, false);
    }

    // ── socket (unchanged from phase 1) ─────────────────────────────────

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

    // Ryzen Nxxx: the model's first digit is what the table keys the socket on.
    private static final Pattern RYZEN_MODEL = Pattern.compile("ryzen \\d+ ([3-9])\\d{3}\\w*");
    // Core i[3579] 1[234]xxx (12th-14th gen desktop model numbers).
    private static final Pattern CORE_MODEL = Pattern.compile("i[3579] (1[234])\\d{2}\\w*");
    // Core i[3579] 1[01]xxx (10th-11th gen).
    private static final Pattern CORE_MODEL_1200 = Pattern.compile("i[3579] (1[01])\\d{2}\\w*");
    // Core i[3579] [89]xxx (8th-9th gen).
    private static final Pattern CORE_MODEL_1151 = Pattern.compile("i[3579] ([89])\\d{3}\\w*");
    // Core Ultra 2xx (the "200 series", Arrow Lake).
    private static final Pattern CORE_ULTRA_MODEL = Pattern.compile("ultra \\d+ 2\\d{2}\\w*");

    private static String cpuSocket(Tokens tokens) {
        String explicit = explicitSocket(tokens);
        if (!explicit.isEmpty()) return explicit;

        String padded = tokens.padded();
        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) >= '7' ? "AM5" : "AM4";
        if (CORE_ULTRA_MODEL.matcher(padded).find()) return "LGA1851";
        if (CORE_MODEL.matcher(padded).find()) return "LGA1700";
        if (CORE_MODEL_1200.matcher(padded).find()) return "LGA1200";
        if (CORE_MODEL_1151.matcher(padded).find()) return "LGA1151";
        return "";
    }

    // ── gama (new in pc-builder-gama) ────────────────────────────────────

    private static Gama gama(Tokens tokens) {
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
        // Xeon es de servidor: no mapea a esta escala de escritorio, queda
        // DESCONOCIDA salvo evidencia medida que diga lo contrario.
        return Gama.DESCONOCIDA;
    }

    // ── marcaChip + generacion (T3b, pc-builder-deep-taxonomy) ───────────

    private static String marcaChip(Tokens tokens) {
        if (tokens.has("intel") || tokens.has("i3") || tokens.has("i5") || tokens.has("i7") || tokens.has("i9")
                || tokens.has("ultra") || tokens.has("celeron") || tokens.has("pentium")) {
            return "INTEL";
        }
        if (tokens.has("amd") || tokens.has("ryzen") || tokens.has("athlon")) return "AMD";
        return "";
    }

    private static int generacion(Tokens tokens) {
        String padded = tokens.padded();

        Matcher ryzen = RYZEN_MODEL.matcher(padded);
        if (ryzen.find()) return ryzen.group(1).charAt(0) - '0';

        // Core Ultra "200 series" (Arrow Lake) viene despues de la 14a
        // generacion; el fabricante no le puso un numero de generacion
        // propio, asi que se mapea a mano en 15.
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
