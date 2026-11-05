package ar.scraper.aggregator.normalize;

final class CalzadoPorModeloClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_SNEAKER_MODELO)) return "Sneaker";

        boolean esZapatilla = texto.contiene("zapatilla") || texto.contiene("sneaker")
                || texto.contiene("calzado") || texto.contiene(" shoe ")
                || texto.contiene(" shoes ")
                || texto.contiene("tenis") || texto.contiene("footwear");

        boolean running = texto.contiene(GarmentTaxonomy.KW_RUNNING_MODELO);
        boolean training = texto.contiene(GarmentTaxonomy.KW_TRAINING_MODELO);
        boolean skate = texto.contiene(GarmentTaxonomy.KW_SKATE_MODELO);
        boolean urbana = texto.contiene(GarmentTaxonomy.KW_URBANA_MODELO);
        if (!esZapatilla && !running && !training && !skate && !urbana) return "";

        if (texto.tieneIndicadorPeso()) return "Alimentos";
        if (running || texto.contiene(GarmentTaxonomy.KW_RUNNING_GENERICO))   return "Zapatilla Running";
        if (training || texto.contiene(GarmentTaxonomy.KW_TRAINING_GENERICO)) return "Zapatilla Entrenamiento";
        if (skate || texto.contiene(GarmentTaxonomy.KW_SKATE_GENERICO))       return "Zapatilla Skate";
        if (urbana || texto.contiene(GarmentTaxonomy.KW_URBANA_GENERICO))     return "Zapatilla Urbana";
        if (texto.contiene(GarmentTaxonomy.KW_SNEAKER_GENERICO))              return "Sneaker";
        if (esZapatilla) return "Zapatilla";
        return "";
    }
}
