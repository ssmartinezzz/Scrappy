package ar.scraper.aggregator.normalize;

final class BanoCocinaClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String sanitario = clasificarSanitario(texto);
        if (!sanitario.isEmpty()) return sanitario;
        String griferia = clasificarGriferia(texto);
        if (!griferia.isEmpty()) return griferia;
        return clasificarAccesorios(texto);
    }

    private String clasificarSanitario(TextoClasificable texto) {
        if (texto.contiene(" mochila ") && texto.contiene(GarmentTaxonomy.KW_MOCHILA_SANITARIA_CALIFICADOR))
            return "Sanitario";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_SANITARIO_LIDER)) return "Sanitario";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_BACHA_LIDER, GarmentTaxonomy.KW_BACHA_VETO)) return "Bacha";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_VANITORY_LIDER))  return "Vanitory";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_BANERA_LIDER, GarmentTaxonomy.KW_BANERA_VETO)) return "Bañera";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MAMPARA_LIDER))   return "Mampara";
        return "";
    }

    private String clasificarGriferia(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_GRIFERIA_LIDER)) return "Grifería";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_DUCHA_LIDER, GarmentTaxonomy.KW_DUCHA_VETO)) return "Ducha";
        return "";
    }

    private String clasificarAccesorios(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ACCESORIO_BANO_LIDER)) return "Accesorio de Baño";
        return "";
    }
}
