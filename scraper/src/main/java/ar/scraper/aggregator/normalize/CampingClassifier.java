package ar.scraper.aggregator.normalize;

final class CampingClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_CARPA_LIDER, GarmentTaxonomy.KW_CARPA_VETO)) return "Carpa";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_BOLSA_DORMIR_LIDER)) return "Bolsa de Dormir";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CONSERVADORA_LIDER)) return "Conservadora";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_TERMO_BOTELLA_LIDER, GarmentTaxonomy.KW_TERMO_BOTELLA_VETO)) return "Termo y Botella";
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_VASO_TERMICO_LIDER, GarmentTaxonomy.KW_VASO_TERMICO_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_TERMO_BOTELLA_VETO)) return "Termo y Botella";
        if (esArticuloDeCamping(texto)) return "Artículo de Camping";
        return "";
    }

    private boolean esArticuloDeCamping(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_CAMPING_VETO)) return false;
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CAMPING_LIDER)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_CAMPING_EN_CONTEXTO_LIDER, GarmentTaxonomy.KW_CAMPING_CALIFICADOR);
    }
}
