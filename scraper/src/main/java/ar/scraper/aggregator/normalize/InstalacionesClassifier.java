package ar.scraper.aggregator.normalize;

final class InstalacionesClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String electricidad = clasificarElectricidad(texto);
        if (!electricidad.isEmpty()) return electricidad;
        String plomeria = clasificarPlomeria(texto);
        if (!plomeria.isEmpty()) return plomeria;
        return clasificarIluminacion(texto);
    }

    private String clasificarElectricidad(TextoClasificable texto) {
        if (lidera(texto, GarmentTaxonomy.KW_TERMICA_LIDER)) return "Térmica";
        if (texto.empiezaCon(GarmentTaxonomy.KW_CAJA_ELECTRICA_LIDER)
                || (lidera(texto, GarmentTaxonomy.KW_TAPA_CIEGA_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_CAJA_CALIFICADOR)))
            return "Caja Eléctrica";
        if (lidera(texto, GarmentTaxonomy.KW_CABLECANAL_LIDER)) return "Cablecanal";
        if (lidera(texto, GarmentTaxonomy.KW_CABLE_ELECTRICO_LIDER)) return "Cable Eléctrico";
        if (lidera(texto, GarmentTaxonomy.KW_FICHA_LIDER)
                || (lidera(texto, GarmentTaxonomy.KW_ZAPATILLA_ELECTRICA_LIDER)
                    && texto.contiene(GarmentTaxonomy.KW_ZAPATILLA_ELECTRICA_CALIFICADOR)))
            return "Ficha y Prolongador";
        if (lidera(texto, GarmentTaxonomy.KW_TAPA_CIEGA_LIDER)
                && texto.contiene(GarmentTaxonomy.KW_TAPA_SANITARIA_CALIFICADOR))
            return "Accesorio de Caño";
        if (esLlaveDeLuz(texto)) return "Llave de Luz";
        return "";
    }

    private boolean esLlaveDeLuz(TextoClasificable texto) {
        return lidera(texto, GarmentTaxonomy.KW_LLAVE_DE_LUZ_LIDER)
                || lidera(texto, GarmentTaxonomy.KW_TAPA_CIEGA_LIDER)
                || calificado(texto, GarmentTaxonomy.KW_MODULO_LIDER, GarmentTaxonomy.KW_MODULO_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_TECLA_LIDER, GarmentTaxonomy.KW_TECLA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_INTERRUPTOR_LIDER, GarmentTaxonomy.KW_INTERRUPTOR_CALIFICADOR);
    }

    private String clasificarPlomeria(TextoClasificable texto) {
        if (texto.contiene(GarmentTaxonomy.KW_CANALETA) && !texto.contiene(GarmentTaxonomy.KW_CANALETA_VETO))
            return "Canaleta";
        if (calificado(texto, GarmentTaxonomy.KW_FLEXIBLE_LIDER, GarmentTaxonomy.KW_FLEXIBLE_CALIFICADOR))
            return "Flexible";
        if (lidera(texto, GarmentTaxonomy.KW_REGULADOR_GAS_LIDER)) return "Regulador de Gas";
        if (lidera(texto, GarmentTaxonomy.KW_LLAVE_DE_PASO_LIDER)) return "Llave de Paso";
        if (calificado(texto, GarmentTaxonomy.KW_TANQUE_LIDER, GarmentTaxonomy.KW_TANQUE_CALIFICADOR))
            return "Tanque de Agua";
        if (calificado(texto, GarmentTaxonomy.KW_BOMBA_LIDER, GarmentTaxonomy.KW_BOMBA_CALIFICADOR))
            return "Bomba de Agua";
        if ((lidera(texto, GarmentTaxonomy.KW_CANO_LIDER) && !texto.contiene(GarmentTaxonomy.KW_CANO_VETO))
                || calificado(texto, GarmentTaxonomy.KW_TUBO_LIDER, GarmentTaxonomy.KW_TUBO_CALIFICADOR))
            return "Caño";
        if (calificado(texto, GarmentTaxonomy.KW_ACCESORIO_CANO_LIDER, GarmentTaxonomy.KW_ACCESORIO_CANO_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_TAPA_LIDER, GarmentTaxonomy.KW_TAPA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_GRAMPA_LIDER, GarmentTaxonomy.KW_GRAMPA_CALIFICADOR)
                || lidera(texto, GarmentTaxonomy.KW_SOPORTE_CANO_LIDER))
            return "Accesorio de Caño";
        return "";
    }

    private String clasificarIluminacion(TextoClasificable texto) {
        if ((lidera(texto, GarmentTaxonomy.KW_ILUMINACION_LIDER) && !texto.contiene(GarmentTaxonomy.KW_ILUMINACION_VETO))
                || calificado(texto, GarmentTaxonomy.KW_COLGANTE_LIDER, GarmentTaxonomy.KW_COLGANTE_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_APLIQUE_LIDER, GarmentTaxonomy.KW_APLIQUE_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_DIFUSOR_LIDER, GarmentTaxonomy.KW_DIFUSOR_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_FAROL_LIDER, GarmentTaxonomy.KW_FAROL_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_ANTORCHA_LIDER, GarmentTaxonomy.KW_ANTORCHA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_GUIRNALDA_LIDER, GarmentTaxonomy.KW_GUIRNALDA_CALIFICADOR)
                || calificado(texto, GarmentTaxonomy.KW_PROYECTOR_LIDER, GarmentTaxonomy.KW_PROYECTOR_CALIFICADOR)
                || texto.contiene(GarmentTaxonomy.KW_ROSCA_DE_LAMPARA))
            return "Iluminación";
        return "";
    }

    private static boolean lidera(TextoClasificable texto, String[] keywords) {
        return texto.sinPrefijoDeVentaEmpiezaCon(keywords);
    }

    private static boolean calificado(TextoClasificable texto, String[] lideres, String[] calificadores) {
        return lidera(texto, lideres) && texto.contiene(calificadores);
    }
}
