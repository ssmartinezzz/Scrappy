package ar.scraper.aggregator.normalize;

final class HerramientasClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String electrica = clasificarElectrica(texto);
        if (!electrica.isEmpty()) return electrica;
        String accesorio = clasificarAccesorio(texto);
        if (!accesorio.isEmpty()) return accesorio;
        return clasificarManual(texto);
    }

    private String clasificarElectrica(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_TALADRO_LIDER))     return "Taladro";
        if (lidera(texto, GarmentTaxonomy.KW_AMOLADORA_LIDER))   return "Amoladora";
        if (lidera(texto, GarmentTaxonomy.KW_SIERRA_LIDER))      return "Sierra";
        if (lidera(texto, GarmentTaxonomy.KW_SOLDADORA_LIDER))   return "Soldadora";
        if (lidera(texto, GarmentTaxonomy.KW_HIDROLAVADORA_LIDER)) return "Hidrolavadora";
        if (lidera(texto, GarmentTaxonomy.KW_HERRAMIENTA_ELECTRICA_LIDER)) return "Herramienta Eléctrica";
        return "";
    }

    private String clasificarAccesorio(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_LIJA_LIDER)) return "Lija";
        if (texto.empiezaCon(GarmentTaxonomy.KW_CAJA_HERRAMIENTAS_LIDER)) return "Caja de Herramientas";
        if (lidera(texto, GarmentTaxonomy.KW_ACCESORIO_HERRAMIENTA_LIDER)
                || calificado(texto, GarmentTaxonomy.KW_MECHA_LIDER, GarmentTaxonomy.KW_MECHA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_BATERIA_HERRAMIENTA_LIDER,
                        GarmentTaxonomy.KW_BATERIA_HERRAMIENTA_CALIFICADOR))
            return "Accesorio de Herramienta";
        return "";
    }

    private String clasificarManual(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_HERRAMIENTA_MANUAL_LIDER)
                || espatulaDeObra(texto)
                || calificado(texto, GarmentTaxonomy.KW_CARRETILLA_LIDER, GarmentTaxonomy.KW_CARRETILLA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_NIVEL_LIDER, GarmentTaxonomy.KW_NIVEL_CALIFICADOR))
            return "Herramienta Manual";
        return "";
    }

    private boolean espatulaDeObra(TextoClasificable texto) {
        return calificado(texto, GarmentTaxonomy.KW_ESPATULA_LIDER, GarmentTaxonomy.KW_ESPATULA_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_ESPATULA_VETO);
    }

    private static boolean lidera(TextoClasificable texto, String[] keywords) {
        return texto.sinPrefijoDeVentaEmpiezaCon(keywords);
    }

    private static boolean calificado(TextoClasificable texto, String[] lideres, String[] calificadores) {
        return lidera(texto, lideres) && texto.contiene(calificadores);
    }
}
