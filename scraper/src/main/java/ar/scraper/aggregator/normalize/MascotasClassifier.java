package ar.scraper.aggregator.normalize;

final class MascotasClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (!texto.contiene(GarmentTaxonomy.KW_MASCOTA_CONTEXTO) || texto.contiene(GarmentTaxonomy.KW_MASCOTA_VETO)) return "";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MASCOTA_ALIMENTO_LIDER)) return "Alimento para Mascota";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MASCOTA_CAMA_LIDER)) return "Cama para Mascota";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MASCOTA_HIGIENE_LIDER)) return "Higiene de Mascota";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MASCOTA_ACCESORIO_LIDER)) return "Accesorio para Mascota";
        return "";
    }
}
