package ar.scraper.aggregator.normalize;

final class JuguetesClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CAMA_ELASTICA_LIDER)) return "Cama Elástica";
        if (esJuguete(texto)) return "Juguete";
        return "";
    }

    private boolean esJuguete(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_JUGUETE_VETO)) return false;
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_JUGUETE_LIDER)
                || texto.lideraConCalificador(GarmentTaxonomy.KW_JUGUETE_CON_MARCA_LIDER, GarmentTaxonomy.KW_JUGUETE_MARCA_CALIFICADOR);
    }
}
