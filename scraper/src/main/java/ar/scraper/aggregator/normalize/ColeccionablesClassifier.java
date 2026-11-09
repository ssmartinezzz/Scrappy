package ar.scraper.aggregator.normalize;

/** Toys that are collected rather than played with. Runs before the garden and garment families. */
final class ColeccionablesClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (esJuegoDeMesa(texto)) return "Juego de Mesa";
        if (esPeluche(texto)) return "Peluche";
        if (esFigura(texto)) return "Figura Coleccionable";
        return "";
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
