package ar.scraper.aggregator.normalize;

final class MueblesClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String dormitorio = clasificarDormitorio(texto);
        if (!dormitorio.isEmpty()) return dormitorio;
        if (texto.lideraUnNombreCompuestoSinVeto(GarmentTaxonomy.KW_ESCRITORIO_LIDER, GarmentTaxonomy.KW_ESCRITORIO_PARTE)) return "Escritorio";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MUEBLE_LIDER, GarmentTaxonomy.KW_MUEBLE_VETO)) return "Mueble";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MESA_LIDER, GarmentTaxonomy.KW_MESA_VETO)) return "Mesa";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_SILLON_LIDER, GarmentTaxonomy.KW_SILLON_VETO)) return "Sillón";
        return "";
    }

    private String clasificarDormitorio(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PLACARD_LIDER)) return "Placard";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_COLCHON_LIDER, GarmentTaxonomy.KW_COLCHON_VETO)) return "Colchón";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_CAMA_LIDER, GarmentTaxonomy.KW_CAMA_VETO)) return "Cama";
        return "";
    }
}
