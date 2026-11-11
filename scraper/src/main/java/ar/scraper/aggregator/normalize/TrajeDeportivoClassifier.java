package ar.scraper.aggregator.normalize;

/** Tracksuits and catsuits sold as one garment. Runs before ConjuntoClassifier, which would file "Conjunto Puma Tracksuit" as a set. */
final class TrajeDeportivoClassifier implements FamiliaClassifier {

    @Override
    public String clasificar(TextoClasificable texto) {
        if (!texto.contiene(GarmentTaxonomy.KW_TRAJE_DEPORTIVO)) return "";
        if (texto.sinPrefijoDeVentaEmpiezaCon(GarmentTaxonomy.KW_TRAJE_DEPORTIVO_PIEZA_LIDER)) return "";
        return "Traje Deportivo";
    }
}
