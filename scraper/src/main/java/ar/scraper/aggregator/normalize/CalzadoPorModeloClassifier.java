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
        boolean xUltra = texto.contiene(GarmentTaxonomy.KW_RUNNING_X_ULTRA);
        boolean skate = texto.contiene(GarmentTaxonomy.KW_SKATE_MODELO);
        boolean urbana = texto.contiene(GarmentTaxonomy.KW_URBANA_MODELO);
        if (!esZapatilla && !running && !xUltra && !training && !skate && !urbana) return "";

        if (texto.tieneIndicadorPeso()) return "Alimentos";
        if (esZapatilla && texto.contiene(GarmentTaxonomy.KW_TREKKING_EXPLICITO)) return "Zapatilla Trekking";
        boolean runningGenerico = texto.contiene(GarmentTaxonomy.KW_RUNNING_GENERICO);
        if (esZapatilla && !running && !runningGenerico && texto.contiene(GarmentTaxonomy.KW_TREKKING_MODELO)) return "Zapatilla Trekking";
        if (running || xUltra || runningGenerico)                             return "Zapatilla Running";
        if (training || texto.contiene(GarmentTaxonomy.KW_TRAINING_GENERICO)) return "Zapatilla Entrenamiento";
        if (skate || texto.contiene(GarmentTaxonomy.KW_SKATE_GENERICO))       return "Zapatilla Skate";
        if (urbana || texto.contiene(GarmentTaxonomy.KW_URBANA_GENERICO))     return "Zapatilla Urbana";
        if (texto.contiene(GarmentTaxonomy.KW_SNEAKER_GENERICO))              return "Sneaker";
        if (esZapatilla) return porModeloDeZapatilla(texto);
        return "";
    }

    private static String porModeloDeZapatilla(TextoClasificable texto) {
        if (esDeporteDeCancha(texto)) return "Zapatilla";
        if (texto.contiene(GarmentTaxonomy.KW_SKATE_ZAPATILLA))    return "Zapatilla Skate";
        if (texto.contiene(GarmentTaxonomy.KW_RUNNING_ZAPATILLA))  return "Zapatilla Running";
        if (texto.contiene(GarmentTaxonomy.KW_TRAINING_ZAPATILLA)) return "Zapatilla Entrenamiento";
        if (texto.contiene(GarmentTaxonomy.KW_URBANA_ZAPATILLA))   return "Zapatilla Urbana";
        return "Zapatilla";
    }

    private static boolean esDeporteDeCancha(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_ZAPATILLA_DEPORTE_VETO)) return true;
        return texto.contiene(" handball ") && !texto.contiene(" handball spezial ");
    }
}
