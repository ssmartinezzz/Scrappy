package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads DDR + total capacity + module type + speed off a RAM's name. */
public final class RamSpecsReader implements LectorDeSpecs {

    private static final Pattern DDR = Pattern.compile(" ddr([345]) ");
    private static final Pattern GB_STANDALONE = Pattern.compile("^(\\d+)gb$");
    private static final Pattern GB_MULTIPLIER = Pattern.compile("^(\\d+)x(\\d+)gb$");

    private static final Pattern MHZ_ATTACHED = Pattern.compile("^(\\d{3,5})mhz$");
    private static final Pattern DIGITS_3_5 = Pattern.compile("^\\d{3,5}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");

    // Whitelist de velocidades DDR reales (medida, pc-builder-gama T3b). Un
    // número pelado de 3-5 dígitos sin "mhz" en ningún lado ("MEMORIA 8GB
    // DDR5 6000 KINGSTON") es indistinguible de un número de modelo — sólo
    // se acepta si además cae en esta whitelist Y el nombre declara DDRn.
    private static final Set<Integer> VELOCIDADES_DDR = Set.of(
            1600, 1866, 2133, 2400, 2666, 2800, 3000, 3200, 3600, 4000, 4266, 4400,
            4800, 5200, 5600, 6000, 6400, 6800, 7200, 7600, 8000);

    @Override
    public String categoria() {
        return "RAM";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", ddr(tokens), "", 0, capacidadGb(tokens), tipoMemoria(tokens),
                Gama.DESCONOCIDA, Certificacion.NINGUNA, velocidadMhz(tokens), TipoAlmacenamiento.DESCONOCIDO);
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

    /**
     * Tres formas reales medidas: "6000MHz" tokeniza junto (un token),
     * "3200 Mhz" tokeniza separado (dos tokens contiguos), y algunos nombres
     * no dicen "mhz" en ningún lado ("MEMORIA 8GB DDR5 6000 KINGSTON"). Las
     * dos primeras formas afirman la velocidad sin ambigüedad porque el
     * propio nombre la etiqueta; la tercera pasa por la whitelist (ver
     * arriba) y exige DDRn declarado, porque un número pelado solo no
     * alcanza para afirmar nada.
     */
    private static int velocidadMhz(Tokens tokens) {
        String[] arr = tokens.array();

        for (String t : arr) {
            Matcher m = MHZ_ATTACHED.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }

        for (int i = 0; i < arr.length - 1; i++) {
            if (DIGITS_3_5.matcher(arr[i]).matches() && arr[i + 1].equals("mhz")) {
                return Integer.parseInt(arr[i]);
            }
        }

        if (!ddr(tokens).isEmpty()) {
            for (String t : arr) {
                if (DIGITS_ONLY.matcher(t).matches()) {
                    int n = Integer.parseInt(t);
                    if (VELOCIDADES_DDR.contains(n)) return n;
                }
            }
        }

        return 0;
    }
}
