package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.regex.Pattern;

/**
 * What a {@code Reloj} row is beyond smartwatch / deportivo (read by {@code SubcategoryResolver}'s keyword tier): a strap
 * ({@code malla}) or a watch that says nothing smart ({@code analógico}, which {@code categoria_rubro} sends to indumentaria).
 * Subs are lowercase like the two that already exist. V143 repeats the two regexes below as the same text over the same
 * tokens ({@code V143RelojAgreementTest} runs both over real names), so a change here needs the same change there.
 */
final class RelojReglas {

    private RelojReglas() {}

    static final String MALLA = "malla";
    static final String ANALOGICO = "analógico";

    private static final String PREFIJO = "(?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*";

    /** A strap leads the name ("Pack 3 Malla Para Reloj ...", "Correa de Titanio"); a "pulsera ... para" is the strap, not the band. */
    static final String ES_MALLA = "^ " + PREFIJO + "(?:(?:malla|mallas|correa|correas|strap|straps) |pulseras? (?:[a-z0-9]+ )?para )";

    /** A watch that leads the name (or a Casio) and carries a plain-watch word. */
    static final String ES_RELOJ_COMUN = "^(?=(?: " + PREFIJO + "(?:reloj|relojes|rejoj) |.* casio ))"
            + "(?=.* (?:casio|g shock|gshock|analogico|analogica|cuarzo|digital|gadnic|sumergible|vintage|hombre|mujer|unisex) )";

    /** Smart or sport words (the keyword tier owns them), makers of smart watches, and things that only share the noun. */
    static final String NO_ES_COMUN = " (?:smart|smartwatch|inteligente|band|gps|deportivo|running|watch|garmin|forerunner|suunto|polar|fitbit"
            + "|amazfit|huawei|xiaomi|samsung|apple|galaxy|redmi|radio|despertador|comparador|cargador|carga|programador|horario"
            + "|reparacion|perfume|clock|localizador|proyector) ";

    private static final Pattern P_MALLA = Pattern.compile(ES_MALLA);
    private static final Pattern P_COMUN = Pattern.compile(ES_RELOJ_COMUN);
    private static final Pattern P_NO_COMUN = Pattern.compile(NO_ES_COMUN);

    static boolean handles(String categoria) {
        return "Reloj".equals(categoria);
    }

    /**
     * Chargers and covers sold for a watch or a band: they leave Reloj for the phone accessories, as there is no watch-accessory
     * category. An Apple Watch one is left to {@code EcosistemaAppleClassifier} ("Accesorio Apple").
     */
    static boolean esAccesorio(TextoClasificable texto) {
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_RELOJ_ACCESORIO_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_RELOJ_SENAL)
                && !texto.contiene(" apple watch ");
    }

    static boolean esMalla(String nombre) {
        return P_MALLA.matcher(tokens(nombre)).find();
    }

    /** {@code analógico} or {@code ""}; the caller has already tried the smartwatch / deportivo keywords. */
    static String analogico(String nombre) {
        String t = tokens(nombre);
        return P_COMUN.matcher(t).find() && !P_NO_COMUN.matcher(t).find() ? ANALOGICO : "";
    }

    private static String tokens(String nombre) {
        return GamingReglas.tokens(" " + AccentStripper.strip(nombre.toLowerCase()) + " ");
    }
}
