package ar.scraper.aggregator.normalize;

/**
 * A game title has no leading noun, so a game is "a platform was named and nothing hardware is the
 * object". Controllers and consoles are decided first so they never fall through to Videojuego.
 */
final class GamingClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (esConsolaPorModelo(texto)) return "Consola";
        if (esEstuche(texto)) return "Otros";
        if (esControl(texto)) return "Joystick";
        if (esConsolaPorNombre(texto)) return "Consola";
        if (esVideojuego(texto)) return "Videojuego";
        return "";
    }

    private boolean esEstuche(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_ESTUCHE_GAMING) && texto.contiene(GarmentTaxonomy.KW_PLATAFORMA);
    }

    private boolean esControl(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_CONTROL_GAMING)
                && (texto.contiene(GarmentTaxonomy.KW_PLATAFORMA) || texto.contiene(GarmentTaxonomy.KW_MARCA_GAMING))
                && !esAccesorio(texto);
    }

    /** A console model wins over a Joy-Con in its own bundle title. */
    private boolean esConsolaPorModelo(TextoClasificable texto) {
        return !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_CONSOLA)
                && texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CONSOLA_LIDER);
    }

    private boolean esConsolaPorNombre(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_ACCESORIO_CONSOLA)) return false;
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CONSOLA_MODELO_LIDER)) return true;
        boolean lideraNombre = texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CONSOLA_NOMBRE_LIDER);
        if (lideraNombre && texto.contiene(GarmentTaxonomy.KW_CONSOLA_CALIFICADOR)) return true;
        return texto.contiene(GarmentTaxonomy.KW_CONSOLA_PALABRA)
                && texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PLATAFORMA);
    }

    private boolean esVideojuego(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_VIDEOJUEGO_LIDER)) return true;
        if (esAccesorio(texto) || texto.contiene(GarmentTaxonomy.KW_CONTROL_GAMING)) return false;
        return texto.contiene(GarmentTaxonomy.KW_PLATAFORMA) || terminaEnSwitchDeJuego(texto);
    }

    private boolean terminaEnSwitchDeJuego(TextoClasificable texto) {
        return texto.normalizado().endsWith(" switch ") && !texto.contiene(GarmentTaxonomy.KW_SWITCH_DE_RED);
    }

    private boolean esAccesorio(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_ACCESORIO_GAMING);
    }
}
