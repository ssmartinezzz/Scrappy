package ar.scraper.aggregator.normalize;

/** Toys that are collected rather than played with. Runs before the garden and garment families. */
final class ColeccionablesClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (esTcg(texto)) return "TCG";
        if (esJuegoDeMesa(texto)) return "Juego de Mesa";
        if (esPeluche(texto)) return "Peluche";
        if (esFigura(texto)) return "Figura Coleccionable";
        return "";
    }

    private boolean esTcg(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_TCG_VETO)) return false;
        return texto.contiene(GarmentTaxonomy.KW_TCG)
                || (texto.contiene(GarmentTaxonomy.KW_TCG_FRANQUICIA)
                    && texto.contiene(GarmentTaxonomy.KW_TCG_CON_FRANQUICIA_CALIFICADOR));
    }

    private boolean esJuegoDeMesa(TextoClasificable texto) {
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_JUEGO_DE_MESA_LIDER)
                || texto.contiene(GarmentTaxonomy.KW_JUEGO_DE_MESA_CALIFICADOR);
    }

    private boolean esPeluche(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_JUGUETE_VETO)) return false;
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PELUCHE_LIDER)) return true;
        return texto.contiene(GarmentTaxonomy.KW_PELUCHE_CALIFICADOR) && !texto.contiene(GarmentTaxonomy.KW_PELUCHE_VETO);
    }

    private boolean esFigura(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_FIGURA_VETO)) return false;
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_FIGURA_LIDER)
                || texto.contiene(GarmentTaxonomy.KW_FIGURA_CALIFICADOR);
    }
}
