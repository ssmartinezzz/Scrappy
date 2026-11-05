package ar.scraper.aggregator.normalize;

final class ParrillaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ACCESORIO_PARRILLA_LIDER)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_ACCESORIO_PARRILLA_EN_CONTEXTO_LIDER, GarmentTaxonomy.KW_ACCESORIO_PARRILLA_CONTEXTO))
            return "Accesorio de Parrilla";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_PARRILLA_LIDER, GarmentTaxonomy.KW_PARRILLA_VETO)) return "Parrilla";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_CARBON_LIDER, GarmentTaxonomy.KW_CARBON_VETO)) return "Carbón y Leña";
        return "";
    }
}
