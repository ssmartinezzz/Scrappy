package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

/**
 * Macstation lists Apple hardware under the model name ("iPad Air 11" (M4) Wi-Fi", "iMac 24"", "Apple Watch Series 11")
 * and its accessories without a category. A model leader is the device. What is sold for an iPad, a Mac, AirPods, an
 * AirTag or an Apple Watch (a case, a strap, a screen protector, a stand, the Pencil) is "Accesorio Apple", whose sub is
 * the device ({@link SubcategoryResolver}); an accessory that also names an iPhone stays with the phone accessories.
 */
final class EcosistemaAppleClassifier implements FamiliaClassifier {

    /** An Apple Watch case size ("para caja de 44 mm"): how a strap says which watch it fits. */
    static final Pattern CAJA_DE_RELOJ = Pattern.compile(" caja de \\d{2} ?mm");

    private static final String ACCESORIO_APPLE = "Accesorio Apple";

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
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_APPLE_ACCESORIO_LIDER)
                && !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DE_EQUIPO_VETO))
            return ACCESORIO_APPLE;
        boolean correaDeReloj = texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CORREA_RELOJ_LIDER)
                && (texto.contiene(" apple watch ") || CAJA_DE_RELOJ.matcher(texto.normalizado()).find());
        boolean accesorioDeEquipo = texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DE_EQUIPO)
                && (texto.contiene(GarmentTaxonomy.KW_APPLE_EQUIPO_REF) || CAJA_DE_RELOJ.matcher(texto.normalizado()).find());
        if ((correaDeReloj || accesorioDeEquipo) && !texto.contiene(GarmentTaxonomy.KW_ACCESORIO_DE_EQUIPO_VETO))
            return ACCESORIO_APPLE;
        return "";
    }
}
