package ar.scraper.aggregator.normalize;

final class NutricionTempranaClassifier implements FamiliaClassifier {

    private final NutricionClassifier nutricion;

    NutricionTempranaClassifier(NutricionClassifier nutricion) {
        this.nutricion = nutricion;
    }

    @Override
    public String clasificar(TextoClasificable texto) {
        // "Remera adidas Cookie" and "Zapatillas Timberland Maple Grove" name a garment, not a food.
        if (texto.empiezaCon(GarmentTaxonomy.KW_ROPA_O_CALZADO_LIDER)) return "";
        if (!texto.contiene(GarmentTaxonomy.KW_ALIMENTO_TEMPRANO)
                && !texto.contiene(GarmentTaxonomy.KW_MARCA_ALIMENTO))
            return "";
        String subcategoria = nutricion.clasificar(texto);
        return subcategoria.isEmpty() ? "Alimentos" : subcategoria;
    }
}
