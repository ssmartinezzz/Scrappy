package ar.scraper.aggregator.normalize;

final class ConjuntoClassifier implements FamiliaClassifier {

    private static final String[][] TORSO = {
        GarmentTaxonomy.KW_PUFFER, GarmentTaxonomy.KW_PILOTO, GarmentTaxonomy.KW_SACO, GarmentTaxonomy.KW_CHALECO,
        GarmentTaxonomy.KW_CAMPERA, GarmentTaxonomy.KW_SWEATER, GarmentTaxonomy.KW_BUZO, GarmentTaxonomy.KW_CASACA,
        GarmentTaxonomy.KW_CHOMBA, GarmentTaxonomy.KW_MUSCULOSA, GarmentTaxonomy.KW_CAMISA, GarmentTaxonomy.KW_REMERA
    };

    private static final String[][] PIERNAS = {
        GarmentTaxonomy.KW_CALZA, GarmentTaxonomy.KW_BAGGY, GarmentTaxonomy.KW_JEAN, GarmentTaxonomy.KW_JOGGING,
        GarmentTaxonomy.KW_BERMUDA, GarmentTaxonomy.KW_SHORT, GarmentTaxonomy.KW_VESTIDO, GarmentTaxonomy.KW_ENTERITO,
        GarmentTaxonomy.KW_POLLERA, GarmentTaxonomy.KW_PANTALON
    };

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_CONJUNTO)) return "Conjunto";
        if (contieneAlguno(texto, TORSO) && contieneAlguno(texto, PIERNAS)) return "Conjunto";
        return "";
    }

    private static boolean contieneAlguno(TextoClasificable texto, String[][] bloques) {
        for (String[] bloque : bloques) {
            if (texto.contiene(bloque)) return true;
        }
        return false;
    }
}
