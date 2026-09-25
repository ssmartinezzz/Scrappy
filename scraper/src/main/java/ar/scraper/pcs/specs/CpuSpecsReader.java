package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;
import java.util.Set;
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
                marcaChip(tokens), generacion(tokens), 0, 0, false, TipoCooler.DESCONOCIDO, nivel(tokens));
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

    /**
     * Athlon "G"/"GE" de escritorio (Bristol Ridge/Picasso) — todos AM4.
     * Medido contra la dev DB (pc-builder-homelab T12, 421 filas activas de
     * {@code CPU}, 38 sin socket): el único CPU de escritorio real sin
     * socket legible es un {@code Athlon 3000G}, y sin esto perdía el veto
     * de {@code ReglaSocket} contra una mother AM5. Lista cerrada a lo
     * medido — no hay Athlon G/GE nuevos, el catálogo es de outlet.
     */
    private static final Set<String> ATHLON_DESKTOP_G =
            Set.of("3000g", "200ge", "220ge", "240ge", "300ge", "320ge");

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
        String athlon = athlonDesktopSocket(tokens);
        if (!athlon.isEmpty()) return athlon;
        return xeonE5ServerSocket(tokens);
    }

    /**
     * Sólo Athlon de escritorio — nunca Xeon (servidor, fuera de esta
     * escala, ver {@link #gama}) ni Athlon móvil (Silver/Gold "U", sin
     * evidencia medida de socket): abstiene para los dos, no inventa.
     */
    private static String athlonDesktopSocket(Tokens tokens) {
        if (!tokens.has("athlon")) return "";
        for (String t : tokens.array()) {
            if (ATHLON_DESKTOP_G.contains(t)) return "AM4";
        }
        return "";
    }

    /**
     * Xeon E5 {@code vNNNN Vn} de servidor (Sandy/Ivy Bridge-EP en adelante) —
     * v3/v4 son LGA2011-3 (Haswell-EP/Broadwell-EP). Nombre real medido
     * (pc-builder-homelab T17): {@code "Procesador Intel Xeon  E5-2699 V3
     * Oem"} sin esto abstenía el socket, y {@code ReglaSocket} no podía
     * vetarlo contra una mother AM5 — mismo defecto que el Athlon 3000G de
     * T12. Acotado a v3/v4 porque es lo único medido; v1/v2 (LGA2011, sin el
     * "-3") y otras familias Xeon (E3/E7/Silver/Gold/Platinum/W) quedan sin
     * evidencia y siguen abstiniendo.
     */
    private static final Pattern XEON_E5_V34 = Pattern.compile(" e5 \\d{3,4} v[34] ");

    private static String xeonE5ServerSocket(Tokens tokens) {
        if (!tokens.has("xeon")) return "";
        if (XEON_E5_V34.matcher(tokens.padded()).find()) return "LGA2011-3";
        return "";
    }

    // ── gama (new in pc-builder-gama) ────────────────────────────────────

    /** Package-visible (not private): {@code MiniPcSpecsReader} reuses this verbatim (pc-builder-homelab T2). */
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
        // Xeon es de servidor: no mapea a esta escala de escritorio, queda
        // DESCONOCIDA salvo evidencia medida que diga lo contrario.
        return Gama.DESCONOCIDA;
    }

    // ── marcaChip + generacion (T3b, pc-builder-deep-taxonomy) ───────────

    /** Package-visible (not private): {@code MiniPcSpecsReader} reuses this verbatim (pc-builder-homelab T2). */
    static String marcaChip(Tokens tokens) {
        if (tokens.has("intel") || tokens.has("i3") || tokens.has("i5") || tokens.has("i7") || tokens.has("i9")
                || tokens.has("ultra") || tokens.has("celeron") || tokens.has("pentium")) {
            return "INTEL";
        }
        if (tokens.has("amd") || tokens.has("ryzen") || tokens.has("athlon")) return "AMD";
        return "";
    }

    /**
     * El escalón de la familia (9|7|5|3), que es lo que hace comparable un
     * {@code i9} con un {@code Ryzen 9}. Athlon/Celeron/Pentium tienen gama
     * BAJA pero no juegan en esta escala: abstienen, y el eje los manda al
     * final (D13). Ver odd/tasks/pc-builder-top-tier.md D1.
     */
    /** Package-visible (not private): {@code MiniPcSpecsReader} reuses this verbatim (pc-builder-homelab T2). */
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
