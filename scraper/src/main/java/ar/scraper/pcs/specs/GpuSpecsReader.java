package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads power tier off a GPU's name, by family + model number band — not a
 * closed model list, so a new generation doesn't fall to DESCONOCIDA just
 * for being unlisted. Phase 1 abstained GPU entirely.
 */
public final class GpuSpecsReader implements LectorDeSpecs {

    private static final Pattern RTX_MODEL = Pattern.compile(" rtx (\\d{3,4}) ");
    private static final Pattern RX_MODEL = Pattern.compile(" rx (\\d{3,4}) ");
    private static final Pattern GTX_MODEL = Pattern.compile(" gtx (\\d{3,4}) ");
    private static final Pattern VRAM_GB = Pattern.compile("^(\\d+)gb$");

    @Override
    public String categoria() {
        return "GPU";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, vram(tokens), "", gama(tokens), Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(),
                marcaChip(tokens), generacion(tokens), 0, 0, false);
    }

    private static Gama gama(Tokens tokens) {
        if (tokens.has("gtx")) return Gama.BAJA;   // cualquier GTX
        if (tokens.has("arc")) return Gama.BAJA;   // cualquier ARC

        String padded = tokens.padded();

        Matcher rtx = RTX_MODEL.matcher(padded);
        if (rtx.find()) {
            int decena = Integer.parseInt(rtx.group(1)) % 100;
            if (decena == 90 || decena == 80 || decena == 70) return Gama.ALTA;
            if (decena == 60) return Gama.MEDIA;
            if (decena == 50) return Gama.BAJA;
            return Gama.DESCONOCIDA;
        }

        Matcher rx = RX_MODEL.matcher(padded);
        if (rx.find()) {
            int modelo = Integer.parseInt(rx.group(1));
            // Radeon numera de DOS maneras y las dos estan vivas en el
            // catalogo (medido en pc-builder-gama): RX 9000 (RDNA4) numera
            // por DECENA, como Nvidia (9070 -> ALTA), mientras que RX
            // 5000-7000 numera por CENTENA (6900 -> ALTA, 7600 -> MEDIA).
            // Una sola regla numerica se come una de las dos series, asi que
            // se ramifica por el primer digito del modelo antes de mirar el
            // tier — nunca un umbral crudo sobre el valor completo.
            if (modelo / 1000 == 9) {
                int decena = modelo % 100;
                if (decena == 90 || decena == 80 || decena == 70) return Gama.ALTA;
                if (decena == 60) return Gama.MEDIA;
                if (decena == 50) return Gama.BAJA;
                return Gama.DESCONOCIDA;
            }

            int centena = modelo % 1000;
            if (centena == 900 || centena == 800) return Gama.ALTA;
            if (centena == 700 || centena == 600) return Gama.MEDIA;
            // "x500 y abajo": solo los multiplos de cien legados (500/400/…) —
            // no un umbral numerico crudo.
            if (centena == 500 || centena == 400 || centena == 300 || centena == 200 || centena == 100) {
                return Gama.BAJA;
            }
            return Gama.DESCONOCIDA;
        }

        return Gama.DESCONOCIDA;
    }

    // ── marcaChip + generacion + VRAM (T3b, pc-builder-deep-taxonomy) ────

    private static String marcaChip(Tokens tokens) {
        if (tokens.has("nvidia") || tokens.has("geforce") || tokens.has("rtx") || tokens.has("gtx")) return "NVIDIA";
        if (tokens.has("radeon") || tokens.has("rx")) return "AMD";
        if (tokens.has("arc")) return "INTEL";
        return "";
    }

    /** El digito de los miles del modelo — RTX/GTX/RX numeran distinto (ver gama()), pero la generacion es siempre esa posicion. */
    private static int generacion(Tokens tokens) {
        String padded = tokens.padded();

        Matcher rtx = RTX_MODEL.matcher(padded);
        if (rtx.find()) return Integer.parseInt(rtx.group(1)) / 1000;

        Matcher gtx = GTX_MODEL.matcher(padded);
        if (gtx.find()) return Integer.parseInt(gtx.group(1)) / 1000;

        Matcher rx = RX_MODEL.matcher(padded);
        if (rx.find()) return Integer.parseInt(rx.group(1)) / 1000;

        return 0;
    }

    private static int vram(Tokens tokens) {
        for (String t : tokens.array()) {
            Matcher m = VRAM_GB.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
