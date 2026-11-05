package ar.scraper.aggregator.normalize;

final class HogarClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_FUNDA_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_FUNDA_CONTEXTO)
                && !texto.contiene(GarmentTaxonomy.KW_FUNDA_VETO))
            return "Funda";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_RESPALDO_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_RESPALDO_CONTEXTO))
            return "Respaldo";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ALMOHADON_LIDER)) return "Almohadón";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MANTA_LIDER))     return "Manta";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MANTEL_LIDER))    return "Mantel";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ALFOMBRA_LIDER)
                && !texto.contiene(GarmentTaxonomy.KW_ALFOMBRA_VETO))
            return "Alfombra";
        return "";
    }
}
