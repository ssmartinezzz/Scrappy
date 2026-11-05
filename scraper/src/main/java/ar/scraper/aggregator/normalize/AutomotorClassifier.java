package ar.scraper.aggregator.normalize;

final class AutomotorClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_LUBRICANTE_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_LUBRICANTE_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_LUBRICANTE_VETO)) return "Lubricante";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_LIQUIDO_AUTO_LIDER, GarmentTaxonomy.KW_LIQUIDO_AUTO_VETO)) return "Líquido Automotor";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_SEGURIDAD_VIAL_LIDER)) return "Seguridad Vial";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_HERRAMIENTA_AUTO_LIDER, GarmentTaxonomy.KW_HERRAMIENTA_AUTO_VETO)) return "Herramienta de Auto";
        if (esBateriaDeAuto(texto)) return "Batería de Auto";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_ESCOBILLA_AUTO_LIDER, GarmentTaxonomy.KW_ESCOBILLA_AUTO_VETO)) return "Escobilla de Auto";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_LIMPIEZA_AUTO_LIDER, GarmentTaxonomy.KW_LIMPIEZA_AUTO_VETO)) return "Limpieza de Auto";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ACCESORIO_AUTO_LIDER)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_ACCESORIO_AUTO_EN_CONTEXTO_LIDER, GarmentTaxonomy.KW_AUTO_CONTEXTO))
            return "Accesorio de Auto";
        return "";
    }

    private boolean esBateriaDeAuto(TextoClasificable texto) {
        return texto.lideraConCalificador(GarmentTaxonomy.KW_BATERIA_AUTO_LIDER, GarmentTaxonomy.KW_BATERIA_AUTO_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_BATERIA_AUTO_VETO);
    }
}
