package ar.scraper.aggregator.normalize;

final class OficinaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_ILUMINACION))     return "Iluminación";
        if (texto.contiene(GarmentTaxonomy.KW_SOPORTE_MONITOR)) return "Soporte Monitor";
        if (texto.contiene(GarmentTaxonomy.KW_SOPORTE_LAPTOP))  return "Soporte Laptop";
        if (texto.contiene(GarmentTaxonomy.KW_ORGANIZACION))    return "Organización";
        if (texto.contiene(GarmentTaxonomy.KW_MAT_ESCRITORIO))  return "Mat Escritorio";
        if (texto.contiene(GarmentTaxonomy.KW_SILLA))           return "Silla";
        if (texto.contiene(GarmentTaxonomy.KW_ESCRITORIO)
                && !texto.contiene(GarmentTaxonomy.KW_ESCRITORIO_PARTE))
            return "Escritorio";
        return "";
    }
}
