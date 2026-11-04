package ar.scraper.aggregator.normalize;

final class BicicletasClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_BICICLETA_LIDER, GarmentTaxonomy.KW_BICICLETA_VETO)) return "Bicicleta";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MONOPATIN_LIDER, GarmentTaxonomy.KW_MONOPATIN_VETO)) return "Monopatín";
        if (esRepuestoDeBicicleta(texto)) return "Repuesto de Bicicleta";
        return "";
    }

    private boolean esRepuestoDeBicicleta(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_REPUESTO_BICI_VETO)) return false;
        if (texto.empiezaCon(GarmentTaxonomy.KW_REPUESTO_BICI_PROPIO_LIDER)) return true;
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_NEUMATICO_BICI_LIDER, GarmentTaxonomy.KW_NEUMATICO_BICI_CONTEXTO)) return true;
        return texto.lideraConCalificador(GarmentTaxonomy.KW_REPUESTO_BICI_GENERICO_LIDER, GarmentTaxonomy.KW_REPUESTO_BICI_CONTEXTO);
    }
}
