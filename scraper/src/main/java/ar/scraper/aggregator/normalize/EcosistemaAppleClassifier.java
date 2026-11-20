package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Macstation lists Apple hardware under the model name ("iPad Air 11" (M4) Wi-Fi", "iMac 24"", "Apple Watch Series 11")
 * and its accessories without a category. A model leader is the device; a strap sold for a watch case is Reloj, like the
 * rest of the catalogue (there is no watch-accessory category); a case or stand "for" an iPad or MacBook is not the device
 * and has no canonical category, so it is an explicit Otros instead of falling to the garment words in its colour names
 * ("azul denim", "capri").
 */
final class EcosistemaAppleClassifier implements FamiliaClassifier {

    private static final Pattern CAJA_DE_RELOJ = Pattern.compile(" caja de \\d{2} ?mm");

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_MINIPC_LIDER))  return "Mini PC";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_PC_LIDER))      return "PC";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_MONITOR_LIDER)) return "Monitor";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_TABLET_LIDER))  return "Tablet";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_AURICULAR_LIDER)) return "Auricular";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_RELOJ_LIDER))   return "Reloj";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_TV_LIDER))      return "TV y Audio";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PARLANTE_MODELO_LIDER)) return "TV y Audio";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_AURICULAR_MODELO_LIDER)) return "Auricular";
        if (texto.contiene(" trackpad "))                                              return "Mouse";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CORREA_RELOJ_LIDER)
                && (texto.contiene(" apple watch ") || CAJA_DE_RELOJ.matcher(texto.normalizado()).find()))
            return "Reloj";
        if (texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DE_EQUIPO)
                && texto.contiene(GarmentTaxonomy.KW_APPLE_EQUIPO_REF)
                && !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DE_EQUIPO_VETO)
                && !texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_PC_LIDER))
            return "Otros";
        return "";
    }
}
