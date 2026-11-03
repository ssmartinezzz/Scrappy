package ar.scraper.aggregator.normalize;

final class NutricionTempranaClassifier implements FamiliaClassifier {

    private final NutricionClassifier nutricion;

    NutricionTempranaClassifier(NutricionClassifier nutricion) {
        this.nutricion = nutricion;
    }

    @Override
    public String clasificar(TextoClasificable texto) {
        if (!texto.contiene(GarmentTaxonomy.KW_ALIMENTO_TEMPRANO)
                && !texto.contiene(GarmentTaxonomy.KW_MARCA_ALIMENTO))
            return "";
        String subcategoria = nutricion.clasificar(texto);
        return subcategoria.isEmpty() ? "Alimentos" : subcategoria;
    }
}
