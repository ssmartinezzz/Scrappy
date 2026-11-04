package ar.scraper.aggregator.normalize;

final class CanchaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_EQUIPAMIENTO_DEPORTIVO_LIDER, GarmentTaxonomy.KW_EQUIPAMIENTO_DEPORTIVO_VETO))
            return "Equipamiento Deportivo";
        return "";
    }
}
