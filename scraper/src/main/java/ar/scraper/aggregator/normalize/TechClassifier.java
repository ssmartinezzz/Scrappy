package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

final class TechClassifier implements FamiliaClassifier {

    private static final Pattern DIAGONAL_NOTEBOOK =
        Pattern.compile("(?<![\\d.,])1[0-8](?:[.,]\\d{1,2})?\\s*(?:″|”|»|\"|''|pulgadas|pulg)");

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
        if (texto.empiezaCon(GarmentTaxonomy.KW_CPU_LIDER))                  return "CPU";
        if (texto.empiezaCon(GarmentTaxonomy.KW_RAM_LIDER) && texto.contiene(GarmentTaxonomy.KW_RAM))
            return "RAM";
        if (texto.contiene(GarmentTaxonomy.KW_RED)
                || (texto.contiene(GarmentTaxonomy.KW_RED_SWITCH) && texto.contiene(GarmentTaxonomy.KW_RED_CONTEXTO)))
            return "Red";
        if (texto.empiezaLiteralmenteCon(GarmentTaxonomy.KW_CABLE_LIDER)
                && !texto.contiene(GarmentTaxonomy.KW_CABLE_VETO))
            return "Cable";
        if (texto.contiene(GarmentTaxonomy.KW_NOTEBOOK) || esFormaDeNotebook(texto))
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

    private static boolean esFormaDeNotebook(TextoClasificable texto) {
        return texto.contiene(ALMACENAMIENTO_INTERNO) && DIAGONAL_NOTEBOOK.matcher(texto.normalizado()).find();
    }
}
