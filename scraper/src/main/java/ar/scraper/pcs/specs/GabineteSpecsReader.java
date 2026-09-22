package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;

import java.util.List;

/**
 * Reads form factor (fase 1, unchanged) and tower size (fase 9, D1) off a
 * Gabinete's name. The two are independent axes and a name can declare
 * either, both, or neither — "MID-TOWER EATX" carries both.
 */
public final class GabineteSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Gabinete";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", formFactor(tokens.padded()), 0, 0, "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA, 0, TipoAlmacenamiento.DESCONOCIDO,
                List.of(), "", 0, 0, 0, false, TipoCooler.DESCONOCIDO, 0,
                tamanio(tokens), 0);
    }

    private static String formFactor(String padded) {
        if (padded.contains(" itx ")) return "ITX";
        if (padded.contains(" matx ") || padded.contains(" m atx ") || padded.contains(" micro atx ")) {
            return "MATX";
        }
        if (padded.contains(" eatx ") || padded.contains(" e atx ")) return "EATX";
        if (padded.contains(" atx ")) return "ATX";
        return "";
    }

    /**
     * El PAR de tokens adyacentes {@code <tamaño> tower} es la única lectura
     * segura, igual que el {@code "m"+"2"} de {@link
     * AlmacenamientoSpecsReader}: "Mid-tower Tg Full Modular" dice FULL
     * pegado a la fuente, no al gabinete, y un {@code has("full")} pelado lo
     * leería como full tower. Medido: el catálogo escribe el tamaño SIEMPRE
     * como "X-tower"/"X tower" — cero filas con la forma pegada
     * ("midtower") y cero con "torre" (2026-09-22).
     */
    private static TamanioGabinete tamanio(Tokens tokens) {
        String[] arr = tokens.array();
        for (int i = 0; i < arr.length - 1; i++) {
            if (!arr[i + 1].equals("tower")) continue;
            switch (arr[i]) {
                case "mini", "micro" -> { return TamanioGabinete.MINI; }
                case "mid", "medium" -> { return TamanioGabinete.MID; }
                case "full" -> { return TamanioGabinete.FULL; }
                default -> { }
            }
        }
        return TamanioGabinete.DESCONOCIDO;
    }
}
