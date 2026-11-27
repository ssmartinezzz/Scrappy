package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Health devices and supplies: thermometers, blood-pressure monitors, oximeters, nebulizers, body scales, massagers, heat pads,
 * adult diapers, condoms, orthopedic insoles, posture correctors, first-aid items. Knee, ankle and wrist supports stay
 * {@code Accesorio Deportivo}: they are sold as sport protection. It runs before the appliance family, so a bathroom
 * scale is {@code Salud} while a kitchen or precision scale stays an appliance; a name is health when it LEADS with the device
 * (or, for a scale, says it weighs people). Oral care, shaving and repellents are not here: they are personal care or the home.
 */
final class SaludClassifier implements FamiliaClassifier {

    static final String CATEGORIA = "Salud";

    private static final String PREFIJO = "(?:(?:kit|set|pack|combo|outlet|x|de|por|[0-9]+|x[0-9]+) )*";

    private static final Pattern DISPOSITIVO = Pattern.compile("^ " + PREFIJO
            + "(?:termometros?|tensiometros?|oximetros?|nebulizador(?:es)?|glucometros?|inhalador(?:es)?|botiquin(?:es)?|barbijos?|tapabocas?"
            + "|tapa bocas?|masajeador(?:es)?|pistolas? masajeadoras?|almohadillas? termicas?|aposito?s?|curitas?|preservativos?|condones?"
            + "|alcohol en gel|gel antibacterial|corrector(?:es)? postural(?:es)?|plantillas? (?:ortopedicas?|de gel|de silicona|antifascitis)) ");
    private static final Pattern MASAJEADOR_EN_EL_NOMBRE = Pattern.compile(
            " (?:massage gun|pistola masajeadora|theragun|masajeador(?:es)? (?:de |para )?(?:pies|cuello|cervical|ojos|cuero cabelludo|espalda))");
    private static final Pattern PANAL_DE_ADULTO = Pattern.compile(" panal(?:es)? (?:[a-z0-9]+ )*(?:adultos?|incontinencia) ");
    private static final Pattern BALANZA = Pattern.compile("^ " + PREFIJO + "balanzas? ");
    private static final Pattern BALANZA_DE_PERSONAS = Pattern.compile(
            " (?:bano|personal|corporal|bioimpedancia|body|fit|grasa|personas?) ");
    private static final Pattern NO_ES_DE_PERSONAS = Pattern.compile(
            " (?:cocina|comercial|precision|cafe|joyeria|equipaje|valija|etiquetas?|industrial|colgante|didactic[oa]|juego) ");
    private static final Pattern NO_ES_SALUD = Pattern.compile(
            " (?:mascotas?|perros?|gatos?|bebes?|rollo de yoga|yoga|foam roller|facial|vibrador|sexual|intimo|ladrillos?|membrana|polvo|parrilla|pared|horno|ambiente|ambiental|carne|alimentos?|cocina|agua|asador|vino|heladera|domestico|pileta|sonda) ");

    @Override
    public String clasificar(TextoClasificable texto) {
        String t = GamingReglas.tokens(texto.normalizado());
        return esDeSalud(t) ? CATEGORIA : "";
    }

    private static boolean esDeSalud(String t) {
        if (BALANZA.matcher(t).find()) {
            return BALANZA_DE_PERSONAS.matcher(t).find() && !NO_ES_DE_PERSONAS.matcher(t).find();
        }
        if (PANAL_DE_ADULTO.matcher(t).find()) return true;
        if (NO_ES_SALUD.matcher(t).find()) return false;
        return DISPOSITIVO.matcher(t).find() || MASAJEADOR_EN_EL_NOMBRE.matcher(t).find();
    }
}
