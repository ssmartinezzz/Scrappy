package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Chargers, power banks, phone holders and phone glass that neither a family, the site's leaf nor the raw category claimed: it only
 * reads what would otherwise be {@code Otros}; precision over recall: a name that could be a tool, vehicle, watch or notebook accessory abstains.
 */
final class AccesorioDeTelefonoClassifier implements FamiliaClassifier {

    private static final Pattern MAH = Pattern.compile("[0-9] ?mah(?![a-z0-9])");
    // Apple sells 12-35 W chargers for phones and 61-140 W ones for the MacBook.
    private static final Pattern POTENCIA_DE_MACBOOK = Pattern.compile(" (?:6[1-9]|[7-9][0-9]|1[0-9]{2}) ?w\\b");

    @Override
    public String clasificar(TextoClasificable texto) {
        if (vetado(texto)) return "";
        return esCargadorOPowerBank(texto) || esBaseDeCarga(texto) || esSoporte(texto) || esVidrio(texto) ? "Celular" : "";
    }

    private static boolean vetado(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_ACCESORIO_TELEFONO_VETO) || texto.contiene(GarmentTaxonomy.KW_CELULAR_VETO)) return true;
        if (texto.contiene(GarmentTaxonomy.KW_ACCESORIO_TELEFONO_VETO_TENSION)
                && !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_TELEFONO_CONECTOR)) return true;
        if (texto.contiene(" iwatch ") && !texto.contiene(" iphone") && !texto.contiene(" celular")) return true;
        return texto.contiene(" apple ") && POTENCIA_DE_MACBOOK.matcher(texto.normalizado()).find();
    }

    /** The noun leads the cable: "Dehuka Cable Cargador" is a cable, "Cargador con cable" is a charger. */
    private static boolean esCargadorOPowerBank(TextoClasificable texto) {
        boolean cargador = texto.contiene(GarmentTaxonomy.KW_CARGADOR_LIDER);
        boolean powerBank = texto.contiene(GarmentTaxonomy.KW_POWER_BANK)
            || cargador && MAH.matcher(texto.normalizado()).find();
        if (!powerBank && !(cargador && texto.contiene(GarmentTaxonomy.KW_CARGADOR_TELEFONO_SENAL))) return false;
        String n = texto.normalizado();
        int cable = primeraPosicion(n, GarmentTaxonomy.KW_CABLE_MENCIONADO);
        return cable < 0 || primeraPosicion(n, GarmentTaxonomy.KW_POWER_BANK, GarmentTaxonomy.KW_CARGADOR_LIDER) < cable;
    }

    /** "Base de carga magnética 3-en-1 Qi2": the dock has no "cargador" noun but a wireless standard in the name. */
    private static boolean esBaseDeCarga(TextoClasificable texto) {
        return texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_BASE_DE_CARGA_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_CARGADOR_TELEFONO_SENAL);
    }

    private static boolean esSoporte(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_PORTACELULAR)) return true;
        return texto.contiene(GarmentTaxonomy.KW_SOPORTE_DE_TELEFONO) && atadoAUnTelefono(texto);
    }

    private static boolean esVidrio(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_VIDRIO_DE_TELEFONO) && atadoAUnTelefono(texto);
    }

    private static boolean atadoAUnTelefono(TextoClasificable texto) {
        return texto.contiene(GarmentTaxonomy.KW_CELULAR_ATADO) || texto.contiene(GarmentTaxonomy.KW_TELEFONO_MARCA);
    }

    private static int primeraPosicion(String texto, String[]... palabras) {
        int mejor = -1;
        for (String[] grupo : palabras) {
            for (String palabra : grupo) {
                int at = texto.indexOf(palabra);
                if (at >= 0 && (mejor < 0 || at < mejor)) mejor = at;
            }
        }
        return mejor;
    }
}
