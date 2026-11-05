package ar.scraper.aggregator.normalize;

final class KayakClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_KAYAK_LIDER)) return "Kayak y Bote";
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_BOTE_LIDER, GarmentTaxonomy.KW_BOTE_CALIFICADOR)) return "Kayak y Bote";
        return "";
    }
}
