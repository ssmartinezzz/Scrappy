package ar.scraper.aggregator.normalize;

final class IndumentariaClassifier implements FamiliaClassifier {

    private static final String[][] NO_CHOMBA = {
        GarmentTaxonomy.KW_MEDIAS, GarmentTaxonomy.KW_GORRA, GarmentTaxonomy.KW_GORRO, GarmentTaxonomy.KW_MOCHILA,
        GarmentTaxonomy.KW_BOLSO, GarmentTaxonomy.KW_BILLETERA, GarmentTaxonomy.KW_CINTURON, GarmentTaxonomy.KW_BUFANDA,
        GarmentTaxonomy.KW_GUANTES, GarmentTaxonomy.KW_LENTES
    };

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_MALLA_SHORT_BANO) && !texto.contiene(GarmentTaxonomy.KW_MALLA_VETO))
            return "Malla";
        if (texto.contiene(GarmentTaxonomy.KW_BOMBACHA) && !texto.contiene(GarmentTaxonomy.KW_BOMBACHA_VETO))
            return "Bombacha";
        if (texto.contiene(GarmentTaxonomy.KW_CALZONCILLO)) return "Calzoncillos";
        if (texto.contiene(GarmentTaxonomy.KW_CORPINO))     return "Corpino";
        if (esMalla(texto)) return "Malla";

        if (texto.contiene(GarmentTaxonomy.KW_PUFFER))   return "Puffer";
        if (texto.contiene(GarmentTaxonomy.KW_PILOTO) && !texto.contiene(GarmentTaxonomy.KW_PILOTO_VETO)) return "Piloto";
        if (texto.contiene(GarmentTaxonomy.KW_TRAJE) && !texto.contiene(GarmentTaxonomy.KW_TRAJE_VETO)) return "Traje";
        if (texto.contiene(GarmentTaxonomy.KW_SACO) && !texto.contiene(GarmentTaxonomy.KW_SACO_VETO)) return "Saco";
        if (texto.contiene(GarmentTaxonomy.KW_CHALECO))  return "Chaleco";
        if (texto.contiene(GarmentTaxonomy.KW_CAMPERA))  return "Campera";
        if (texto.contiene(GarmentTaxonomy.KW_SWEATER) && !texto.contiene(GarmentTaxonomy.KW_SWEATER_VETO)) return "Sweater";
        if (texto.contiene(GarmentTaxonomy.KW_BUZO) && !texto.contiene(GarmentTaxonomy.KW_BUZO_VETO)) return "Buzo";
        if (texto.contiene(GarmentTaxonomy.KW_CASACA))   return "Casaca";
        if (texto.contiene(GarmentTaxonomy.KW_CHOMBA)
                || (texto.contiene(GarmentTaxonomy.KW_CHOMBA_MARCA) && esContextoChomba(texto)))
            return "Chomba";
        if (texto.contiene(GarmentTaxonomy.KW_MUSCULOSA)) return "Musculosa";
        if (texto.contiene(GarmentTaxonomy.KW_CAMISA) && !texto.contiene(GarmentTaxonomy.KW_CAMISA_VETO)) return "Camisa";
        if (texto.contiene(GarmentTaxonomy.KW_REMERA) && !texto.contiene(GarmentTaxonomy.KW_REMERA_VETO)) return "Remera";

        if (texto.contiene(GarmentTaxonomy.KW_CALZA))    return "Calza";
        if (texto.contiene(GarmentTaxonomy.KW_BAGGY))    return "Baggy";
        if (texto.contiene(GarmentTaxonomy.KW_JEAN))     return "Jean";
        if (texto.contiene(GarmentTaxonomy.KW_JOGGING))  return "Jogging";
        if (texto.contiene(GarmentTaxonomy.KW_BERMUDA))  return "Bermuda";
        if (texto.contiene(GarmentTaxonomy.KW_SHORT))    return "Short";
        if (texto.contiene(GarmentTaxonomy.KW_VESTIDO) && !texto.empiezaCon(GarmentTaxonomy.KW_VESTIDO_VETO_LIDER)) return "Vestido";
        if (texto.contiene(GarmentTaxonomy.KW_ENTERITO)) return "Enterito";
        if (texto.contiene(GarmentTaxonomy.KW_POLLERA))  return "Pollera";
        if (texto.contiene(GarmentTaxonomy.KW_PANTALON) && !texto.contiene(GarmentTaxonomy.KW_PANTALON_VETO)) return "Pantalón";
        return "";
    }

    private static boolean esMalla(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_MALLA)) return !texto.contiene(GarmentTaxonomy.KW_MALLA_VETO);
        return texto.contiene(GarmentTaxonomy.KW_MALLA_BANO) && texto.contiene(GarmentTaxonomy.KW_MALLA_BANO_PRENDA);
    }

    private static boolean esContextoChomba(TextoClasificable texto) {
        for (String[] accesorio : NO_CHOMBA) {
            if (texto.contiene(accesorio)) return false;
        }
        return true;
    }
}
