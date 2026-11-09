package ar.scraper.aggregator.normalize;

/** Cámaras de acción, digitales y de auto, y todo lo que se vende para ellas. Corre antes del guard no-textil. */
final class CamarasClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CAMARA_VETO_LIDER)) return "";
        if (texto.contiene(GarmentTaxonomy.KW_CAMARA_DISPOSITIVO) || texto.contiene(GarmentTaxonomy.KW_CAMARA_MARCA))
            return "Cámara";
        return "";
    }
}
