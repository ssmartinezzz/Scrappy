package ar.scraper.aggregator.normalize;

final class ConjuntoClassifier implements FamiliaClassifier {

    private static final String[][] TORSO = {
        GarmentTaxonomy.KW_PUFFER, GarmentTaxonomy.KW_PILOTO, GarmentTaxonomy.KW_SACO, GarmentTaxonomy.KW_CHALECO,
        GarmentTaxonomy.KW_CAMPERA, GarmentTaxonomy.KW_SWEATER, GarmentTaxonomy.KW_BUZO, GarmentTaxonomy.KW_CASACA,
        GarmentTaxonomy.KW_CHOMBA, GarmentTaxonomy.KW_MUSCULOSA,
        GarmentTaxonomy.KW_TOP_DEPORTIVO, GarmentTaxonomy.KW_TOP, GarmentTaxonomy.KW_CAMISA, GarmentTaxonomy.KW_REMERA
    };

    private static final String[][] PIERNAS = {
        GarmentTaxonomy.KW_CALZA, GarmentTaxonomy.KW_BAGGY, GarmentTaxonomy.KW_JEAN, GarmentTaxonomy.KW_JOGGING,
        GarmentTaxonomy.KW_BERMUDA, GarmentTaxonomy.KW_SHORT, GarmentTaxonomy.KW_VESTIDO, GarmentTaxonomy.KW_ENTERITO,
        GarmentTaxonomy.KW_POLLERA, GarmentTaxonomy.KW_PANTALON
    };

    private static final String[][] ROPA_SUELTA = {
        GarmentTaxonomy.KW_MEDIAS, GarmentTaxonomy.KW_CALZONCILLO, GarmentTaxonomy.KW_BOMBACHA, GarmentTaxonomy.KW_CORPINO,
        GarmentTaxonomy.KW_MALLA, GarmentTaxonomy.KW_GORRO, GarmentTaxonomy.KW_GORRA
    };

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_CONJUNTO_FUERTE)) return "Conjunto";
        // A leading garment noun is one garment: "Pantalón Topper Pro Fleece", "Medias Topper Pack X 3" and
        // "Short adidas Workout All Set" carry the other garment, the pack or the set as fabric or model name.
        if (texto.empiezaCon(GarmentTaxonomy.KW_ROPA_O_CALZADO_LIDER)) return "";
        if (contieneAlguno(texto, TORSO) && contieneAlguno(texto, PIERNAS)) return "Conjunto";
        // "Set x4 Individuales" or "Kit de Reparación" is a kit of something, not a garment set.
        if (texto.contiene(GarmentTaxonomy.KW_CONJUNTO_DEBIL) && (contieneAlguno(texto, TORSO)
                || contieneAlguno(texto, PIERNAS) || contieneAlguno(texto, ROPA_SUELTA))) return "Conjunto";
        return "";
    }

    private static boolean contieneAlguno(TextoClasificable texto, String[][] bloques) {
        for (String[] bloque : bloques) {
            if (texto.contiene(bloque)) return true;
        }
        return false;
    }
}
