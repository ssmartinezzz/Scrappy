package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

/** Reads form factor off a Gabinete's name. Unchanged from phase 1. */
public final class GabineteSpecsReader implements LectorDeSpecs {

    @Override
    public String categoria() {
        return "Gabinete";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", formFactor(tokens.padded()), 0, 0, "",
                Gama.DESCONOCIDA, Certificacion.NINGUNA);
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
}
