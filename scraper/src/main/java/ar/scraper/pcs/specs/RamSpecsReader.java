package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads DDR + total capacity + module type off a RAM's name. Unchanged from phase 1. */
public final class RamSpecsReader implements LectorDeSpecs {

    private static final Pattern DDR = Pattern.compile(" ddr([345]) ");
    private static final Pattern GB_STANDALONE = Pattern.compile("^(\\d+)gb$");
    private static final Pattern GB_MULTIPLIER = Pattern.compile("^(\\d+)x(\\d+)gb$");

    @Override
    public String categoria() {
        return "RAM";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", ddr(tokens), "", 0, capacidadGb(tokens), tipoMemoria(tokens),
                Gama.DESCONOCIDA, Certificacion.NINGUNA);
    }

    private static String ddr(Tokens tokens) {
        Matcher m = DDR.matcher(tokens.padded());
        return m.find() ? "DDR" + m.group(1) : "";
    }

    /**
     * "64GB (2x32GB)" states the total explicitly — take that. Only when no
     * standalone total is stated does the pack multiplier ("2x16GB" alone)
     * get multiplied out.
     */
    private static int capacidadGb(Tokens tokens) {
        for (String t : tokens.array()) {
            Matcher m = GB_STANDALONE.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        for (String t : tokens.array()) {
            Matcher m = GB_MULTIPLIER.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1)) * Integer.parseInt(m.group(2));
        }
        return 0;
    }

    private static String tipoMemoria(Tokens tokens) {
        return tokens.has("sodimm") ? "SODIMM" : "DIMM";
    }
}
