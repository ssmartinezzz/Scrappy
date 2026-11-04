package ar.scraper.aggregator.normalize;

final class PiletaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_QUIMICO_PILETA_LIDER)) return "Químico de Pileta";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ACCESORIO_PILETA_LIDER)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_ACCESORIO_PILETA_EN_CONTEXTO_LIDER, GarmentTaxonomy.KW_ACCESORIO_PILETA_CALIFICADOR)
                || esParaLaPileta(texto))
            return "Accesorio de Pileta";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_INFLABLE_LIDER, GarmentTaxonomy.KW_INFLABLE_VETO)) return "Inflable";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_PILETA_LIDER, GarmentTaxonomy.KW_PILETA_VETO)) return "Pileta";
        return "";
    }

    private boolean esParaLaPileta(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_PILETA_CONTEXTO)
                && !texto.contiene(GarmentTaxonomy.KW_PILETA_CONTEXTO_VETO)
                && !texto.empiezaCon(GarmentTaxonomy.KW_PRENDA_LIDER);
    }
}
