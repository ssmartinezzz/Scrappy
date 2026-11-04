package ar.scraper.aggregator.normalize;

final class TextilDecoClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String textil = clasificarTextil(texto);
        if (!textil.isEmpty()) return textil;
        return clasificarDecoracion(texto);
    }

    private String clasificarTextil(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_ROPA_DE_CAMA_LIDER, GarmentTaxonomy.KW_ROPA_DE_CAMA_VETO))
            return "Ropa de Cama";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_TOALLA_LIDER, GarmentTaxonomy.KW_TOALLA_VETO)) return "Toalla";
        if (esCortina(texto)) return "Cortina";
        return "";
    }

    private boolean esCortina(TextoClasificable texto) {
        return texto.lideraSinVeto(GarmentTaxonomy.KW_CORTINA_LIDER, GarmentTaxonomy.KW_CORTINA_VETO)
                || (texto.lideraConCalificador(GarmentTaxonomy.KW_ROLLER_LIDER, GarmentTaxonomy.KW_ROLLER_CALIFICADOR)
                    && !texto.contiene(GarmentTaxonomy.KW_ROLLER_VETO));
    }

    private String clasificarDecoracion(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_ESPEJO_LIDER, GarmentTaxonomy.KW_ESPEJO_VETO)) return "Espejo";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_DECORACION_LIDER, GarmentTaxonomy.KW_DECORACION_VETO)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_ARBOL_LIDER, GarmentTaxonomy.KW_ARBOL_CALIFICADOR))
            return "Decoración";
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_RELOJ_DECO_LIDER, GarmentTaxonomy.KW_RELOJ_DECO_CALIFICADOR))
            return "Decoración";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_BAZAR_LIDER, GarmentTaxonomy.KW_BAZAR_VETO)) return "Bazar";
        return "";
    }
}
