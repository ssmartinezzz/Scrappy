package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Bakeware and kitchen containers (asaderas, moldes, bandejas de horno, fuentes). They read as {@code Fuente} (the PC power
 * supply) or abstain, so the noun has to be claimed here. A name is Bazar only when it LEADS with the piece, or with
 * {@code fuente} plus a household signal: a bare "Fuente 650W ..." stays a power supply.
 */
final class BazarCocinaClassifier implements FamiliaClassifier {

    private static final String PREFIJO = "(?:(?:kit|set|pack|combo|outlet|x|de|por|[0-9]+|x[0-9]+) )*";

    private static final Pattern PIEZA = Pattern.compile("^ " + PREFIJO
            + "(?:asaderas?|pizzeras?|budineras?|budineros?|tarteras?|torteras?|flaneras?|paneras?|moldes?|placas? asaderas?"
            + "|placas? silicona|esterillas? barra|base plato para tortas|preparacion y decoracion de tortas) ");
    private static final Pattern NO_ES_DE_COCINA = Pattern.compile(
            " (?:ladrillos?|hormigon|cemento|yeso|concreto|resina|jabon|velas?|hielo|inyeccion|playa|arena|masilla|unas|esculpidas) ");
    private static final Pattern BATEA = Pattern.compile("^ " + PREFIJO + "bateas? (?:.* )?(?:madera|alpaca|bambu|panera|copetinero) ");
    private static final Pattern BANDEJA = Pattern.compile("^ " + PREFIJO + "bandejas? ");
    private static final Pattern BANDEJA_DE_COCINA = Pattern.compile(
            " (?:horno|silicona|airfryer|air fryer|freidora|asador|bambu|nouvelle cuisine|cocina|alpaca|pizza) ");
    private static final Pattern RECIPIENTE = Pattern.compile("^ " + PREFIJO + "(?:recipientes?|envases?) ");
    private static final Pattern RECIPIENTE_DE_COCINA = Pattern.compile(" (?:vidrio|hermeticos?|nouvelle cuisine|tapa) ");
    private static final Pattern FUENTE = Pattern.compile("^ " + PREFIJO + "fuentes? ");
    private static final Pattern FUENTE_ASADERA = Pattern.compile(" (?:fuentes? asaderas?|asaderas? fuentes?) ");
    private static final Pattern SENAL_DE_COCINA = Pattern.compile(
            " (?:pyr o rey|oxford|panelux|argenprom|nouvelle cuisine|dona clara|hudson|carol|lunita|tramontina|marmicoc"
            + "|horno|vidrio|porcelana|asas|antiadherente|rectangular|cuadrada|oval|ovalada|redonda|ensaladera"
            + "|[0-9]+ ?(?:l|lt|lts|litros)) ");

    @Override
    public String clasificar(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        return esDeCocina(t) ? "Bazar" : "";
    }

    private static boolean esDeCocina(String t) {
        if (PIEZA.matcher(t).find()) return !NO_ES_DE_COCINA.matcher(t).find();
        if (BATEA.matcher(t).find()) return true;
        if (BANDEJA.matcher(t).find()) return BANDEJA_DE_COCINA.matcher(t).find();
        if (RECIPIENTE.matcher(t).find()) return RECIPIENTE_DE_COCINA.matcher(t).find();
        if (FUENTE.matcher(t).find()) return SENAL_DE_COCINA.matcher(t).find();
        return FUENTE_ASADERA.matcher(t).find();
    }
}
