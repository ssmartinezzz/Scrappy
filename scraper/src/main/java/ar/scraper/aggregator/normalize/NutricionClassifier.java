package ar.scraper.aggregator.normalize;

final class NutricionClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        String t = sinMarcasQueNombranProteina(texto.normalizado());
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_CREATINA))         return "Creatina";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_BARRA))   return "Barra Proteica";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_PANCAKE)) return "Pancake Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_SNACK))   return "Snack Proteico";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_GAINERS))          return "Gainer";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PRE_WORKOUT_SUP))  return "Pre-Workout";
        boolean cabezaDeProteina = GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA);
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_VEGETAL)
                || (cabezaDeProteina
                    && GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_VEGETAL_RECLAMO)))
            return "Proteína Vegetal";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA)
                || (cabezaDeProteina
                    && (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA_PROCESO)
                     || GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_PROTEINA_ISOLADA_MARCA))))
            return "Proteína Isolada";
        if (cabezaDeProteina)                                                 return "Proteína";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COLAGENO))         return "Colágeno";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_MAGNESIO))         return "Magnesio";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_BCAA_SUP))         return "BCAA";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_VITAMINAS))        return "Vitaminas";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_QUEMADORES))       return "Quemadores";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_SUPLEMENTO))       return "Suplemento";
        if (GarmentTaxonomy.anyMatch(t, GarmentTaxonomy.KW_COMIDA))           return "Alimentos";
        return "";
    }

    private static String sinMarcasQueNombranProteina(String t) {
        String limpio = t;
        for (String marca : GarmentTaxonomy.KW_MARCA_CON_PROTEINA_EN_EL_NOMBRE) {
            if (limpio.contains(marca)) limpio = limpio.replace(marca, " ");
        }
        return limpio;
    }
}
