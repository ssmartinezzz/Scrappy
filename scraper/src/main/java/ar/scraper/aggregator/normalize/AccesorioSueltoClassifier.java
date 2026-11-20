package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Phone accessories whose noun does not lead the name: "Speck Presidio Pro iPhone XS Max", "Griffin Survivor Funda
 * Transparente iPhone 12" (brand-first, so CelularClassifier misses them). Runs after TechClassifier, so a cable, a
 * controller, a headset or a microphone "for iPhone" keeps its own category.
 */
final class AccesorioSueltoClassifier implements FamiliaClassifier {

    private static final Pattern MODELO_IPHONE = Pattern.compile(
        " (?:para|for|con) (?:el )?1[1-8] ?(?:pro|plus|max|mini|air)\\b| magsafe ");

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_CELULAR_VETO)) return "";
        // A cable "for iPhone" is a cable: leave it to the rules that read cables, not to the phone bucket.
        if (texto.contiene(GarmentTaxonomy.KW_CABLE_MENCIONADO)) return "";
        if (texto.contiene(" iphone ")) return "Celular";
        // "ItSkins Hybrid R Nylon Funda con MagSafe para 15 Pro Max": the model number stands in for the word iPhone.
        return texto.contiene(GarmentTaxonomy.KW_FUNDA_DE_TELEFONO) && MODELO_IPHONE.matcher(texto.normalizado()).find()
            ? "Celular" : "";
    }
}
