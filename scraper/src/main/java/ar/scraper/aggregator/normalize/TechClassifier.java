package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

final class TechClassifier implements FamiliaClassifier {

    private static final Pattern DIAGONAL_NOTEBOOK =
        Pattern.compile("(?<![\\d.,])1[0-8](?:[.,]\\d{1,2})?\\s*(?:″|”|»|\"|''|pulgadas|pulg)");

    // Boundaries are non-[a-z0-9], not spaces: kits glue the parts with "+" ("5700X+B550+16GB").
    private static final Pattern SENAL_CPU = Pattern.compile(
        "(?<![a-z0-9])(?:procesador|ryzen|core i[3579]|core ultra|athlon|pentium|celeron|i[3579][- ]?[0-9]{4,5}[a-z]{0,2})(?![a-z0-9])");
    private static final Pattern SENAL_MOTHER = Pattern.compile(
        "(?<![a-z0-9])(?:mother|motherboard|placa madre|mainboard|mobo"
        + "|(?:[ab]3[2-7]0|a[56]20|b[3-6]50|b[3-6]60|b[78][3-6]0|h[3-8]10|z[3-9]90|x[3-8]70)[me]?)(?![a-z0-9])");
    private static final String[] MARCA_DE_PAQUETE = {" combo ", " kit "};

    private static final String[] ALMACENAMIENTO_INTERNO = {" ssd ", " disco ", " emmc ", " nvme ", " sata "};

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.empiezaCon(GarmentTaxonomy.KW_PRENDA_LIDER)) return "";
        if ((texto.contiene(" para gabinete ") && texto.empiezaCon(GarmentTaxonomy.KW_GABINETE_ACCESORIO_LIDER))
                || texto.empiezaCon(GarmentTaxonomy.KW_ACCESORIO_LIDER)
                || texto.empiezaCon(GarmentTaxonomy.KW_SERVICIO_LIDER))
            return "";
        if (texto.empiezaCon(GarmentTaxonomy.KW_MINIPC_LIDER))               return "Mini PC";
        if (texto.empiezaCon(GarmentTaxonomy.KW_PC_LIDER))                   return "PC";
        if (esComboDeActualizacion(texto))                                   return "Combo de Actualización";
        if (texto.empiezaCon(GarmentTaxonomy.KW_CPU_LIDER))                  return "CPU";
        if (texto.empiezaCon(GarmentTaxonomy.KW_RAM_LIDER) && texto.contiene(GarmentTaxonomy.KW_RAM))
            return "RAM";
        if (texto.contiene(GarmentTaxonomy.KW_RED)
                || (texto.contiene(GarmentTaxonomy.KW_RED_SWITCH) && texto.contiene(GarmentTaxonomy.KW_RED_CONTEXTO)))
            return "Red";
        if (texto.empiezaLiteralmenteCon(GarmentTaxonomy.KW_CABLE_LIDER)
                && !texto.contiene(GarmentTaxonomy.KW_CABLE_VETO))
            return "Cable";
        if (!texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CARGADOR_LIDER)
                && (texto.contiene(GarmentTaxonomy.KW_NOTEBOOK) || esFormaDeNotebook(texto)))
            return "Notebook";
        if (texto.contiene(GarmentTaxonomy.KW_TABLET))                       return "Tablet";
        if (texto.contiene(GarmentTaxonomy.KW_CAMARA))                       return "Cámara";
        if (texto.contiene(GarmentTaxonomy.KW_PC))                           return "PC";
        if (texto.contiene(GarmentTaxonomy.KW_MONITOR))                      return "Monitor";
        if (texto.contiene(GarmentTaxonomy.KW_GPU))                          return "GPU";
        if (texto.contiene(GarmentTaxonomy.KW_MOTHERBOARD))                  return "Motherboard";
        if (texto.empiezaCon(GarmentTaxonomy.KW_FUENTE_LIDER) && !texto.contiene(GarmentTaxonomy.KW_FUENTE_VETO))
            return "Fuente";
        if (texto.contiene(GarmentTaxonomy.KW_GABINETE) && !texto.contiene(GarmentTaxonomy.KW_GABINETE_VETO))
            return "Gabinete";
        if (texto.contiene(GarmentTaxonomy.KW_FUENTE) && !texto.contiene(GarmentTaxonomy.KW_FUENTE_VETO))
            return "Fuente";
        if (texto.empiezaCon(GarmentTaxonomy.KW_ALMACENAMIENTO_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_ALMACENAMIENTO))
            return "Almacenamiento";
        if (texto.empiezaCon(GarmentTaxonomy.KW_AURICULAR_LIDER))            return "Auricular";
        if (texto.empiezaCon(GarmentTaxonomy.KW_JOYSTICK_LIDER))             return "Joystick";
        if (texto.contiene(GarmentTaxonomy.KW_COOLER))                       return "Cooler";
        if (texto.contiene(GarmentTaxonomy.KW_CPU))                          return "CPU";
        if (texto.contiene(GarmentTaxonomy.KW_RAM))                          return "RAM";
        if (texto.contiene(GarmentTaxonomy.KW_ALMACENAMIENTO))               return "Almacenamiento";
        if (texto.contiene(GarmentTaxonomy.KW_IMPRESION) && !texto.contiene(GarmentTaxonomy.KW_IMPRESION_VETO)) return "Impresión";
        if (texto.contiene(GarmentTaxonomy.KW_UPS))                          return "UPS";
        if (texto.contiene(GarmentTaxonomy.KW_JOYSTICK)
                || (texto.contiene(GarmentTaxonomy.KW_VOLANTE_GENERICO) && texto.contiene(GarmentTaxonomy.KW_VOLANTE_CONTEXTO)))
            return "Joystick";
        if (texto.contiene(GarmentTaxonomy.KW_MICROFONO))                    return "Micrófono";
        if (texto.contiene(GarmentTaxonomy.KW_WEBCAM))                       return "Webcam";
        if (texto.contiene(GarmentTaxonomy.KW_AURICULAR))                    return "Auricular";
        if (texto.contiene(GarmentTaxonomy.KW_MOUSEPAD))                     return "Mousepad";
        if (texto.contiene(GarmentTaxonomy.KW_TECLADO))                      return "Teclado";
        if (texto.contiene(GarmentTaxonomy.KW_MOUSE)
                || (texto.contiene(GarmentTaxonomy.KW_MOUSE_GENERICO) && !texto.contiene(GarmentTaxonomy.KW_MOUSE_VETO)))
            return "Mouse";
        if (texto.contiene(GarmentTaxonomy.KW_RELOJ))                        return "Reloj";
        return "";
    }

    /**
     * CPU + motherboard sold as one item. It must run before the leading-noun rules: "Kit Mother ... +
     * Procesador ..." would otherwise be a Motherboard, "Combo ... + Cooler" a Cooler. Needs the three
     * signals together, so a CPU with its stock cooler stays a CPU and a lone motherboard a Motherboard.
     * Full PCs never get here (PC leader above) and a kit that carries a case or a "pc gamer" is a PC.
     */
    private static boolean esComboDeActualizacion(TextoClasificable texto) {
        String n = texto.normalizado();
        return (texto.contiene(MARCA_DE_PAQUETE) || n.indexOf('+') >= 0)
                && SENAL_CPU.matcher(n).find()
                && SENAL_MOTHER.matcher(n).find()
                && !texto.contiene(GarmentTaxonomy.KW_PC)
                && !texto.contiene(" gabinete ");
    }

    private static boolean esFormaDeNotebook(TextoClasificable texto) {
        return texto.contiene(ALMACENAMIENTO_INTERNO) && DIAGONAL_NOTEBOOK.matcher(texto.normalizado()).find();
    }
}
