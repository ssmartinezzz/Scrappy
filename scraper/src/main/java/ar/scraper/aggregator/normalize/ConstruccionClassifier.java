package ar.scraper.aggregator.normalize;

import java.util.regex.Pattern;

final class ConstruccionClassifier implements FamiliaClassifier {

    private static final Pattern DIMENSIONES = Pattern.compile("\\d\\s*x\\s*\\d");

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_PLANTA_ARTIFICIAL)
                || texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PLANTA_ARTIFICIAL_LIDER))
            return "Planta Artificial";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CESPED_LIDER))   return "Césped Sintético";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_ADHESIVO_LIDER)) return "Adhesivo";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_MOLDURA_LIDER))  return "Moldura";
        String material = clasificarMaterial(texto);
        if (!material.isEmpty()) return material;
        String pintura = clasificarPintura(texto);
        if (!pintura.isEmpty()) return pintura;
        String abertura = clasificarAbertura(texto);
        if (!abertura.isEmpty()) return abertura;
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PISO_LIDER)
                || texto.contiene(GarmentTaxonomy.KW_PISO))                       return "Piso";
        if (texto.contiene(GarmentTaxonomy.KW_WALL_PANEL))                        return "Wall Panel";
        if (texto.contiene(GarmentTaxonomy.KW_PLACA_DECORATIVA)
                || (texto.contiene(GarmentTaxonomy.KW_PLACA)
                    && texto.contiene(GarmentTaxonomy.KW_PLACA_CALIFICADOR)
                    && !texto.contiene(GarmentTaxonomy.KW_PLACA_VETO)))
            return "Placa Decorativa";
        if (texto.contiene(GarmentTaxonomy.KW_VINILO_DECORATIVO))                 return "Vinilo Decorativo";
        return "";
    }

    private String clasificarMaterial(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_CERAMICO_LIDER) && DIMENSIONES.matcher(texto.normalizado()).find())
            return "Cerámico";
        if (lidera(texto, GarmentTaxonomy.KW_PORCELANATO_LIDER)) return "Porcelanato";
        if (lidera(texto, GarmentTaxonomy.KW_PASTINA_LIDER))     return "Pastina";
        if (lidera(texto, GarmentTaxonomy.KW_REVESTIMIENTO_LIDER)) return "Revestimiento";
        if (lidera(texto, GarmentTaxonomy.KW_TABLERO_LIDER))     return "Tablero";
        if (lidera(texto, GarmentTaxonomy.KW_PLACA_DE_YESO_LIDER)) return "Placa de Yeso";
        if (lidera(texto, GarmentTaxonomy.KW_AISLANTE_LIDER))    return "Aislante";
        if (lidera(texto, GarmentTaxonomy.KW_LADRILLO_LIDER))    return "Ladrillo";
        if (lidera(texto, GarmentTaxonomy.KW_ARIDO_LIDER))       return "Árido";
        if (lidera(texto, GarmentTaxonomy.KW_CEMENTO_LIDER))     return "Cemento";
        if (lidera(texto, GarmentTaxonomy.KW_HIERRO_LIDER))      return "Hierro";
        if (lidera(texto, GarmentTaxonomy.KW_IMPERMEABILIZANTE_LIDER)) return "Impermeabilizante";
        if (lidera(texto, GarmentTaxonomy.KW_CHAPA_LIDER))       return "Chapa";
        if (lidera(texto, GarmentTaxonomy.KW_MADERA_LIDER))      return "Madera";
        if (lidera(texto, GarmentTaxonomy.KW_PERFILERIA_LIDER)
                || (lidera(texto, GarmentTaxonomy.KW_SOLERA_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_SOLERA_CALIFICADOR)))
            return "Perfilería";
        return "";
    }

    private String clasificarPintura(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_ENDUIDO_LIDER))   return "Enduido";
        if (lidera(texto, GarmentTaxonomy.KW_DILUYENTE_LIDER)) return "Diluyente";
        if (lidera(texto, GarmentTaxonomy.KW_ACCESORIO_PINTURA_LIDER)
                || (lidera(texto, GarmentTaxonomy.KW_PINCEL_BROCHA_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_PINCEL_BROCHA_CALIFICADOR))
                || (lidera(texto, GarmentTaxonomy.KW_RODILLO_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_RODILLO_CALIFICADOR)))
            return "Accesorio de Pintura";
        if ((lidera(texto, GarmentTaxonomy.KW_PINTURA_LIDER)
                || (lidera(texto, GarmentTaxonomy.KW_AEROSOL_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_AEROSOL_PINTURA_CALIFICADOR)))
                && !texto.contiene(GarmentTaxonomy.KW_PINTURA_VETO))
            return "Pintura";
        if (lidera(texto, GarmentTaxonomy.KW_EMPAPELADO_LIDER)) return "Empapelado";
        return "";
    }

    private String clasificarAbertura(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_PUERTA_LIDER) && !texto.contiene(GarmentTaxonomy.KW_PUERTA_VETO))
            return "Puerta";
        if (lidera(texto, GarmentTaxonomy.KW_VENTANA_LIDER) && !texto.contiene(GarmentTaxonomy.KW_VENTANA_VETO))
            return "Ventana";
        if (lidera(texto, GarmentTaxonomy.KW_PORTON_LIDER))  return "Portón";
        if (lidera(texto, GarmentTaxonomy.KW_MOSQUITERO_LIDER)
                && !texto.contiene(GarmentTaxonomy.KW_MOSQUITERO_VETO))
            return "Mosquitero";
        if (lidera(texto, GarmentTaxonomy.KW_TOLDO_LIDER) && !texto.contiene(GarmentTaxonomy.KW_TOLDO_VETO))
            return "Toldo";
        if (lidera(texto, GarmentTaxonomy.KW_ESCALERA_LIDER) && !texto.contiene(GarmentTaxonomy.KW_ESCALERA_VETO))
            return "Escalera";
        return "";
    }

    private static boolean lidera(TextoClasificable texto, String[] keywords) {
        return texto.sinPrefijoDeVentaEmpiezaCon(keywords);
    }
}
