package ar.scraper.aggregator.normalize;

final class OrganizacionHogarClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (texto.empiezaCon(GarmentTaxonomy.KW_SISTEMA_MODULAR_LIDER)) return "Sistema Modular";
        String almacenaje = clasificarAlmacenaje(texto);
        if (!almacenaje.isEmpty()) return almacenaje;
        return clasificarLavadero(texto);
    }

    private String clasificarAlmacenaje(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_ESTANTERIA_LIDER, GarmentTaxonomy.KW_ESTANTERIA_VETO)) return "Estantería";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_COLGADOR_LIDER, GarmentTaxonomy.KW_COLGADOR_VETO)) return "Colgador";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_BASURERO_LIDER)) return "Basurero";
        if (esCajaOCanasto(texto)) return "Caja y Canasto";
        return "";
    }

    private boolean esCajaOCanasto(TextoClasificable texto) {
        return !texto.contiene(GarmentTaxonomy.KW_CAJA_CANASTO_VETO)
                && (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_CAJA_CANASTO_LIDER)
                    || texto.empiezaCon(GarmentTaxonomy.KW_CAJA_LIDER));
    }

    private String clasificarLavadero(TextoClasificable texto) {
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_TENDEDERO_LIDER)) return "Tendedero";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_LIMPIEZA_LIDER, GarmentTaxonomy.KW_LIMPIEZA_VETO))
            return "Artículo de Limpieza";
        return "";
    }
}
