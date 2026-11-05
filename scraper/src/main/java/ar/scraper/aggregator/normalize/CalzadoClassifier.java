package ar.scraper.aggregator.normalize;

final class CalzadoClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_BOTIN))    return "Botines";
        if (texto.contiene(GarmentTaxonomy.KW_BOTIN_GENERICO) && esContextoBotin(texto)) return "Botines";
        if (texto.contiene(GarmentTaxonomy.KW_BORCEGO))  return "Borcego";
        if (texto.contiene(GarmentTaxonomy.KW_BORCEGO_MARCA) && esContextoBorcego(texto)) return "Borcego";
        if (texto.contiene(GarmentTaxonomy.KW_PANTUFLA)) return "Pantufla";
        if (texto.contiene(GarmentTaxonomy.KW_ZAPATO))   return "Zapato";
        if (texto.contiene(GarmentTaxonomy.KW_MOCASIN))  return "Mocasin";
        if (texto.contiene(GarmentTaxonomy.KW_SANDALIA)) return "Sandalia";
        if (texto.contiene(GarmentTaxonomy.KW_OJOTA)
                || (texto.contiene(GarmentTaxonomy.KW_OJOTA_MARCA) && esContextoOjota(texto)))
            return "Ojotas";
        if (texto.contiene(GarmentTaxonomy.KW_BOTA))     return "Botas";
        return "";
    }

    private static boolean esContextoBotin(TextoClasificable texto) {
        return texto.contiene("botin") || texto.contiene("futbol") || texto.contiene("tachon")
            || texto.contiene("cleats") || texto.contiene("cancha");
    }

    private static boolean esContextoBorcego(TextoClasificable texto) {
        return texto.contiene("borcego") || texto.contiene("bota") || texto.contiene("boot")
            || texto.contiene("hiker") || texto.contiene("hiking") || texto.contiene("calzado");
    }

    private static boolean esContextoOjota(TextoClasificable texto) {
        return texto.contiene("ojota") || texto.contiene("sandalia") || texto.contiene("chancla")
            || texto.contiene("chinelo") || texto.contiene("slide") || texto.contiene("flip flop")
            || texto.contiene("zueco") || texto.contiene("rasteira") || texto.contiene("babucha");
    }
}
