package ar.scraper.aggregator.normalize;

final class ConstruccionClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_PLANTA_ARTIFICIAL)
                || texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PLANTA_ARTIFICIAL_LIDER))
            return "Planta Artificial";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CESPED_LIDER))   return "Césped Sintético";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ADHESIVO_LIDER)) return "Adhesivo";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MOLDURA_LIDER))  return "Moldura";
        if (texto.contiene(GarmentTaxonomy.KW_PISO))                              return "Piso";
        if (texto.contiene(GarmentTaxonomy.KW_PLACA_DECORATIVA)
                || (texto.contiene(GarmentTaxonomy.KW_PLACA)
                    && texto.contiene(GarmentTaxonomy.KW_PLACA_CALIFICADOR)
                    && !texto.contiene(GarmentTaxonomy.KW_PLACA_VETO)))
            return "Placa Decorativa";
        if (texto.contiene(GarmentTaxonomy.KW_VINILO_DECORATIVO))                 return "Vinilo Decorativo";
        return "";
    }
}
