package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

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

    @Override
    public String categoria() {
        return "GPU";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", 0, 0, "", gama(tokens), Certificacion.NINGUNA);
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
}
