package ar.scraper.aggregator.normalize;

final class JardinClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String cultivo = clasificarCultivo(texto);
        if (!cultivo.isEmpty()) return cultivo;
        String herramental = clasificarHerramental(texto);
        if (!herramental.isEmpty()) return herramental;
        return clasificarExterior(texto);
    }

    private String clasificarCultivo(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MACETA_LIDER, GarmentTaxonomy.KW_MACETA_VETO)) return "Maceta";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_SEMILLA_LIDER, GarmentTaxonomy.KW_SEMILLA_VETO)) return "Semilla";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_PLANTA_LIDER, GarmentTaxonomy.KW_PLANTA_VETO)) return "Planta";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_TIERRA_LIDER, GarmentTaxonomy.KW_TIERRA_VETO)) return "Tierra y Fertilizante";
        return "";
    }

    private String clasificarHerramental(TextoClasificable texto) {
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_RIEGO_LIDER, GarmentTaxonomy.KW_RIEGO_VETO)) return "Riego";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_PULVERIZADOR_LIDER)) return "Pulverizador";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MAQUINA_JARDIN_LIDER, GarmentTaxonomy.KW_MAQUINA_JARDIN_VETO)) return "Máquina de Jardín";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_HERRAMIENTA_JARDIN_LIDER, GarmentTaxonomy.KW_HERRAMIENTA_JARDIN_VETO)) return "Herramienta de Jardín";
        return "";
    }

    private String clasificarExterior(TextoClasificable texto) {
        if (texto.lideraConCalificador(GarmentTaxonomy.KW_CERCO_LIDER, GarmentTaxonomy.KW_CERCO_CALIFICADOR)) return "Cerco y Media Sombra";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_DECO_JARDIN_LIDER, GarmentTaxonomy.KW_DECO_JARDIN_VETO)) return "Decoración de Jardín";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_GAZEBO_LIDER)) return "Gazebo y Sombrilla";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_REPOSERA_LIDER, GarmentTaxonomy.KW_REPOSERA_VETO)) return "Reposera y Hamaca";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_DEPOSITO_JARDIN_LIDER, GarmentTaxonomy.KW_DEPOSITO_JARDIN_VETO)) return "Depósito de Jardín";
        if (texto.lideraSinVeto(GarmentTaxonomy.KW_MUEBLE_EXTERIOR_LIDER, GarmentTaxonomy.KW_MUEBLE_EXTERIOR_VETO)) return "Mueble de Exterior";
        return "";
    }
}
