package ar.scraper.aggregator.normalize;

final class EquipamientoDeportivoClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_PELOTA)) return "Pelota";
        if (texto.contiene(GarmentTaxonomy.KW_PALETA)) return "Paleta";
        return "";
    }
}
