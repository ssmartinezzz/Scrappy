package ar.scraper.aggregator.normalize;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.regex.Pattern;

/**
 * The {@code Motorizado} sub of {@code Escritorio}: only a name that says the desk has a motor. "Regulable" or "ajustable" alone
 * is height-adjustable, which a crank or a gas spring also does. V142 repeats both regexes as the same text over the same tokens
 * ({@code V142MousepadYMatAgreementTest} runs both over real names).
 */
final class EscritorioReglas {

    private EscritorioReglas() {}

    static final String MOTORIZADO = "Motorizado";

    static final String CON_MOTOR = " (?:motor|motores|motorizad[oa]s?|electric[oa]s?) ";

    static final String SIN_MOTOR = " (?:sin motor|manual(?:es)?) ";

    private static final Pattern P_CON_MOTOR = Pattern.compile(CON_MOTOR);
    private static final Pattern P_SIN_MOTOR = Pattern.compile(SIN_MOTOR);

    static boolean handles(String categoria) {
        return "Escritorio".equals(categoria);
    }

    /** {@code ""} when the name does not prove a motor. */
    static String sub(String nombre) {
        String t = GamingReglas.tokens(" " + AccentStripper.strip(nombre.toLowerCase()) + " ");
        return P_CON_MOTOR.matcher(t).find() && !P_SIN_MOTOR.matcher(t).find() ? MOTORIZADO : "";
    }
}
