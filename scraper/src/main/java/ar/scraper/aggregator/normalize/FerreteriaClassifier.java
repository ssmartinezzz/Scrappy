package ar.scraper.aggregator.normalize;

final class FerreteriaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String fijacion = clasificarFijacion(texto);
        if (!fijacion.isEmpty()) return fijacion;
        String herraje = clasificarHerraje(texto);
        if (!herraje.isEmpty()) return herraje;
        if (lidera(texto, GarmentTaxonomy.KW_CINTA_ADHESIVA_LIDER)) return "Cinta Adhesiva";
        return clasificarProteccion(texto);
    }

    private String clasificarFijacion(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_TORNILLO_LIDER)) return "Tornillo";
        if ((lidera(texto, GarmentTaxonomy.KW_TARUGO_LIDER) && !texto.contiene(GarmentTaxonomy.KW_TARUGO_VETO))
                || calificado(texto, GarmentTaxonomy.KW_TACO_LIDER, GarmentTaxonomy.KW_TACO_CALIFICADOR))
            return "Tarugo";
        if (lidera(texto, GarmentTaxonomy.KW_CLAVO_LIDER)) return "Clavo";
        if (lidera(texto, GarmentTaxonomy.KW_FIJACION_LIDER) || texto.empiezaCon(GarmentTaxonomy.KW_FIJACION_LIDER))
            return "Fijación";
        return "";
    }

    private String clasificarHerraje(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_BISAGRA_LIDER))  return "Bisagra";
        if (lidera(texto, GarmentTaxonomy.KW_CERRADURA_LIDER)) return "Cerradura";
        if (lidera(texto, GarmentTaxonomy.KW_CANDADO_LIDER) || texto.empiezaCon(GarmentTaxonomy.KW_CANDADO_LIDER))
            return "Candado";
        if (lidera(texto, GarmentTaxonomy.KW_HERRAJE_LIDER)
                || calificado(texto, GarmentTaxonomy.KW_PATA_LIDER, GarmentTaxonomy.KW_PATA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_RUEDA_LIDER, GarmentTaxonomy.KW_RUEDA_CALIFICADOR))
            return "Herraje";
        return "";
    }

    private String clasificarProteccion(TextoClasificable texto) {
        if (calificado(texto, GarmentTaxonomy.KW_GUANTE_LIDER, GarmentTaxonomy.KW_GUANTE_TRABAJO_CALIFICADOR))
            return "Guante de Trabajo";
        if (lidera(texto, GarmentTaxonomy.KW_PROTECCION_LIDER)
                || calificado(texto, GarmentTaxonomy.KW_ANTIPARRA_LIDER,
                        GarmentTaxonomy.KW_ANTIPARRA_PROTECCION_CALIFICADOR))
            return "Protección Personal";
        return "";
    }

    private static boolean lidera(TextoClasificable texto, String[] keywords) {
        return texto.sinPrefijoDeVentaEmpiezaCon(keywords);
    }

    private static boolean calificado(TextoClasificable texto, String[] lideres, String[] calificadores) {
        return lidera(texto, lideres) && texto.contiene(calificadores);
    }
}
