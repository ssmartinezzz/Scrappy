package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/** Books and manga. Runs before the garment families, which own words like "one piece" and "short". */
final class LibrosClassifier implements FamiliaClassifier {

    /** "... 05", "... 003 - IVREA": a volume number closing the title, optionally followed by a publisher. */
    private static final Pattern VOLUMEN_FINAL = Pattern.compile("\\s\\d{1,3}(?:\\s+-\\s+[a-z ]+)?\\s*$");

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_LIBRO_VETO_LIDER)
                || texto.contiene(GarmentTaxonomy.KW_LIBRO_VETO)) return "";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_LIBRO_LIDER)
                || texto.contiene(GarmentTaxonomy.KW_LIBRO_CALIFICADOR)
                || esSerieConVolumen(texto)) return "Libro";
        return "";
    }

    private boolean esSerieConVolumen(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_MANGA_SERIE) && VOLUMEN_FINAL.matcher(texto.normalizado()).find();
    }
}
