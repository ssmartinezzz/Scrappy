package ar.scraper.aggregator.normalize;

final class ElectrodomesticosClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String clima = clasificarClima(texto);
        if (!clima.isEmpty()) return clima;
        return clasificarLineaBlanca(texto);
    }

    private String clasificarClima(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_TERMOTANQUE_LIDER)) return "Termotanque";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CALEFON_LIDER)) return "Calefón";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_AIRE_ACONDICIONADO_LIDER)) return "Aire Acondicionado";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_VENTILADOR_LIDER, GarmentTaxonomy.KW_VENTILADOR_VETO)) return "Ventilador";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CALEFACTOR_LIDER)) return "Calefactor";
        return "";
    }

    private String clasificarLineaBlanca(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_HELADERA_LIDER)) return "Heladera";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_LAVARROPAS_LIDER)) return "Lavarropas";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ASPIRADORA_LIDER)) return "Aspiradora";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_CAMPANA_LIDER, GarmentTaxonomy.KW_CAMPANA_VETO)) return "Campana y Extractor";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PEQUENO_ELECTRO_LIDER)) return "Pequeño Electrodoméstico";
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_COCINA_LIDER, GarmentTaxonomy.KW_COCINA_CALIFICADOR)
                && !texto.contiene(GarmentTaxonomy.KW_COCINA_VETO)) return "Cocina y Horno";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_TV_AUDIO_LIDER, GarmentTaxonomy.KW_TV_AUDIO_VETO)) return "TV y Audio";
        return "";
    }
}
