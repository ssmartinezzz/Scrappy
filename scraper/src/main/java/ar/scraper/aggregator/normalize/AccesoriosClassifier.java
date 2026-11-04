package ar.scraper.aggregator.normalize;

final class AccesoriosClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_PERFUME))              return "Perfume";
        if (texto.contiene(GarmentTaxonomy.KW_BILLETERA))            return "Billetera";
        if (texto.contiene(GarmentTaxonomy.KW_RINONERA))             return "Riñonera";
        if (texto.contiene(GarmentTaxonomy.KW_MOCHILA) && !texto.contiene(GarmentTaxonomy.KW_MOCHILA_VETO)) return "Mochila";
        if (texto.contiene(GarmentTaxonomy.KW_BOLSO))                return "Bolso";
        if (texto.contiene(GarmentTaxonomy.KW_CINTURON))             return "Cinturón";
        if (texto.contiene(GarmentTaxonomy.KW_BUFANDA))              return "Bufanda";
        if (texto.contiene(GarmentTaxonomy.KW_GUANTES))              return "Guantes";
        if (texto.contiene(GarmentTaxonomy.KW_LENTES))               return "Lentes";
        if (texto.contiene(GarmentTaxonomy.KW_GORRO))                return "Gorro";
        if (texto.contiene(GarmentTaxonomy.KW_GORRA))                return "Gorra";
        if (texto.contiene(GarmentTaxonomy.KW_MEDIAS) && !texto.contiene(GarmentTaxonomy.KW_MEDIAS_VETO)) return "Medias";
        if (texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DEPORTIVO)
                && !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DEPORTIVO_VETO))
            return "Accesorio Deportivo";
        if (texto.contiene(GarmentTaxonomy.KW_MUSCULOSA_TARDIA))     return "Musculosa";
        if (texto.contiene(GarmentTaxonomy.KW_REMERA_TARDIA))        return "Remera";
        if (texto.contiene(GarmentTaxonomy.KW_CORPINO_TARDIO))       return "Corpino";
        return "";
    }
}
