package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Teléfonos y lo que se vende para ellos. Un accesorio sólo es Celular si el nombre lo ata a un teléfono
 * ("Funda Magsafe para iPhone 16"); un cargador, un cable o unos auriculares genéricos siguen en su categoría.
 */
final class CelularClassifier implements FamiliaClassifier {

    private static final Pattern TIE_GALAXY = Pattern.compile(" galaxy [asmzf]\\d{1,3}\\b| galaxy z ");
    private static final Pattern GALAXY_BOOK = Pattern.compile(" galaxy book ?\\d");
    private static final Pattern MODELO_GALAXY = Pattern.compile(
        " galaxy (?:[asmzf]\\d{1,3}\\b|z (?:fold|flip)|xcover|note ?\\d+)");
    private static final Pattern MODELO_DE_MARCA = Pattern.compile(
        " (?:samsung [asmzf]\\d{2}\\b|moto (?:[ge]\\d|edge|razr)|motorola (?:moto [ge]\\d|edge|razr)|redmi (?:note |a|c)?\\d|poco [a-z]\\d|xiaomi (?:redmi|poco) (?:note |a|c)?[a-z]?\\d)");

    @Override
    public String clasificar(TextoClasificable texto) {
        boolean accesorio = texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CELULAR_ACCESORIO_LIDER);
        if (!accesorio) {
            String galaxy = otroEquipoGalaxy(texto);
            if (!galaxy.isEmpty()) return galaxy;
        }
        if (texto.contiene(GarmentTaxonomy.KW_CELULAR_VETO)) return "";
        if (accesorio) {
            return texto.contiene(GarmentTaxonomy.KW_CELULAR_ATADO) || TIE_GALAXY.matcher(texto.normalizado()).find()
                ? "Celular" : "";
        }
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CELULAR_LIDER)
                || reacondicionado(texto)
                || MODELO_GALAXY.matcher(texto.normalizado()).find()
                || MODELO_DE_MARCA.matcher(texto.normalizado()).find())
            return "Celular";
        return "";
    }

    private static boolean reacondicionado(TextoClasificable texto) {
        return texto.normalizado().startsWith(" reacondicionado ")
            && (texto.contiene(" iphone ") || texto.contiene(" smartphone ") || texto.contiene(" celular "));
    }

    private static String otroEquipoGalaxy(TextoClasificable texto) {
        if (!texto.contiene(" galaxy ")) return "";
        if (texto.contiene(" galaxy tab ")) return "Tablet";
        if (GALAXY_BOOK.matcher(texto.normalizado()).find()) return "Notebook";
        if (texto.contiene(" galaxy watch") || texto.contiene(" galaxy fit")) return "Reloj";
        if (texto.contiene(" galaxy buds")) return "Auricular";
        return "";
    }
}
