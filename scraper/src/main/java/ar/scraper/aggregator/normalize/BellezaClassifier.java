package ar.scraper.aggregator.normalize;

/**
 * Maquillaje, Cuidado de la Piel and Cuidado del Cabello. Runs before NonTextileGuard (which vetoes "crema", "gel",
 * "shampoo" and "protector solar" on purpose for garments), so every rule here starts from KW_BELLEZA_VETO.
 * Perfume and Desodorante stay with AccesoriosClassifier: a row that names a fragrance is not claimed here.
 */
final class BellezaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_BELLEZA_VETO)) return "";
        if (texto.contiene(GarmentTaxonomy.KW_PIEL_LABIAL)) return "Cuidado de la Piel";
        if ((texto.contiene(GarmentTaxonomy.KW_MAQUILLAJE) || texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MAQUILLAJE_LIDER))
                && !texto.contiene(GarmentTaxonomy.KW_MAQUILLAJE_VETO))
            return "Maquillaje";
        if (texto.contiene(GarmentTaxonomy.KW_CABELLO) || texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CABELLO_LIDER))
            return "Cuidado del Cabello";
        if (texto.contiene(GarmentTaxonomy.KW_PIEL)) return "Cuidado de la Piel";
        return "";
    }
}
