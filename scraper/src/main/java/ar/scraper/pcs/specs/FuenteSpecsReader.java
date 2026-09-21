package ar.scraper.pcs.specs;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads watts (phase 1, unchanged) + 80 PLUS certification off a Fuente's name. */
public final class FuenteSpecsReader implements LectorDeSpecs {

    private static final Pattern WATTS = Pattern.compile("^(\\d{3,4})w$");

    @Override
    public String categoria() {
        return "Fuente";
    }

    @Override
    public TechSpecs leer(Tokens tokens) {
        return new TechSpecs("", "", "", watts(tokens), 0, "", Gama.DESCONOCIDA, certificacion(tokens));
    }

    private static int watts(Tokens tokens) {
        for (String t : tokens.array()) {
            Matcher m = WATTS.matcher(t);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    // Formas reales medidas en el catalogo: "80 Plus Bronze", "80 PLUS Gold
    // V3", "80+ Platinum" — el "+"/espacio ya desaparece en el tokenizado,
    // asi que el chequeo es un token suelto. "Bronce" (español) tambien
    // aparece en el catalogo junto al nombre oficial "Bronze".
    private static Certificacion certificacion(Tokens tokens) {
        if (tokens.has("titanium")) return Certificacion.TITANIUM;
        if (tokens.has("platinum")) return Certificacion.PLATINUM;
        if (tokens.has("gold")) return Certificacion.GOLD;
        if (tokens.has("silver")) return Certificacion.SILVER;
        if (tokens.has("bronze") || tokens.has("bronce")) return Certificacion.BRONZE;
        if (tokens.has("white")) return Certificacion.WHITE;
        return Certificacion.NINGUNA;
    }
}
