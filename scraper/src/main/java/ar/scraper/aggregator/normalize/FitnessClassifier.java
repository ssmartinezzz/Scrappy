package ar.scraper.aggregator.normalize;

final class FitnessClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (esPesas(texto)) return "Pesas";
        if (esAccesorioDeFitness(texto)) return "Accesorio de Fitness";
        return "";
    }

    private boolean esPesas(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_PESAS_LIDER, GarmentTaxonomy.KW_PESAS_VETO)) return true;
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_DISCO_PESA_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_DISCO_PESA_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_DISCO_PESA_VETO)) return true;
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_TOBILLERA_LIDER, GarmentTaxonomy.KW_TOBILLERA_PESO)) return true;
        return texto.lideraConCalificador(GarmentTaxonomy.KW_BARRA_LIDER, GarmentTaxonomy.KW_BARRA_PESAS_CALIFICADOR);
    }

    private boolean esAccesorioDeFitness(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ACCESORIO_FITNESS_LIDER)) return true;
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_BARRA_LIDER, GarmentTaxonomy.KW_BARRA_ACCESORIO_CALIFICADOR)) return true;
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_LADRILLO_YOGA_LIDER, GarmentTaxonomy.KW_LADRILLO_YOGA_CALIFICADOR)) return true;
        return texto.lideraConCalificador(GarmentTaxonomy.KW_COLCHONETA_LIDER, GarmentTaxonomy.KW_COLCHONETA_FITNESS_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_COLCHONETA_FITNESS_VETO);
    }
}
