package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * School and office stationery: notebooks, binders, pencil cases, paper, agendas, pens, markers, staplers, calculators and art
 * supplies sold by the stationer's. It runs LAST in the name families, so a notebook computer, a pet "carpeta" or a makeup pencil
 * (claimed earlier) never get here. A name is stationery when it LEADS with the piece; a word that is also a hardware or beauty
 * noun ("carpeta", "block", "lapiz", "clips") needs a stationery word beside it.
 */
final class LibreriaClassifier implements FamiliaClassifier {

    static final String CATEGORIA = "Librería";

    private static final String PREFIJO = "(?:(?:kit|set|pack|combo|outlet|x|de|por|[0-9]+|x[0-9]+) )*";

    private static final Pattern PIEZA = Pattern.compile("^ " + PREFIJO
            + "(?:cuadernos?|cartucheras?|canoplas?|resmas?|agendas?|libretas?|anotador(?:es)?|biblioratos?|resaltador(?:es)?|lapiceras?|lapices"
            + "|biromes?|boligrafos?|marcador(?:es)?|abrochadoras?|perforadoras?|calculadoras?|sacapuntas|compases|plasticolas?"
            + "|voligomas?|boligomas?|fibrones|crayones|acuarelas?|temperas?|plastilinas?|folios?|post ?it|notas adhesivas|utiles escolares"
            + "|set escolar|kit escolar|tijeras? escolar(?:es)?|sobres? (?:oficio|carta|ingles|tarjeta|americano)|etiquetas? (?:autoadhesivas?|adhesivas?)"
            + "|papel (?:a4|a3|fluo|para diseno|glossy|fotografico|afiche|crepe|barrilete|ilustracion|canson)) ");
    private static final Pattern CON_SENAL = Pattern.compile("^ " + PREFIJO
            + "(?:carpetas?|blocks?|clips?|blinder clips?|lapiz|reglas?) ");
    private static final Pattern SENAL_DE_LIBRERIA = Pattern.compile(
            " (?:escolar(?:es)?|oficio|a4|a5|solapas?|anillos?|3x40|hojas?|rayad[oa]s?|cuadriculad[oa]s?|liso|exito|rexon|binder|blinder"
            + "|papel|dibujo|colores|faber|staedtler|n3|n5) ");
    private static final Pattern NO_ES_DE_LIBRERIA = Pattern.compile(
            " (?:labial|labios|cejas|ojos|corrector|optico|stylus|multimetro|grabador|cortaceramica|carpintero|carpinteria|mascotas?|perros?"
            + "|adiestramiento|nasal|ropa|night light|cemento|membrana|aluminio|acero|plegable|medir|metalica) ");

    /** A 3D pen is a "lápiz" sold with coloured filaments. */
    private static final Pattern NO_ES_UN_LAPIZ = Pattern.compile(" (?:3d|3dpen|filamentos?) ");

    @Override
    public String clasificar(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        return esDeLibreria(t) ? CATEGORIA : "";
    }

    /** The name leads with stationery. It also runs where the non-textile guard abstains ("Libreta Aros Premium" has "aros"). */
    static boolean esDeLibreria(TextoClasificable texto) {
        return esDeLibreria(GamingReglas.tokens(texto.normalizado()));
    }

    private static boolean esDeLibreria(String t) {
        if (NO_ES_DE_LIBRERIA.matcher(t).find()) return false;
        if (PIEZA.matcher(t).find()) return true;
        return CON_SENAL.matcher(t).find() && SENAL_DE_LIBRERIA.matcher(t).find() && !NO_ES_UN_LAPIZ.matcher(t).find();
    }
}
