package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;

/**
 * Ranks a slot's candidates by its technical-quality axes ({@link
 * EjesTecnicos}), then price ascending, then url ascending — price is
 * ALWAYS the tiebreak, never the objective (pc-builder-gama T3b; replaces
 * {@code CriterioScoreMlPrecioUrl}, which ranked by {@code baseMlScore} — a
 * PRICE percentile — and so picked the cheapest candidate of any slot).
 * Every candidate arrives already filtered to the slot's own category, so
 * {@link Product#categoria()} is what {@link TechSpecsParser} reads.
 */
public class CriterioPorEjesTecnicos implements CriterioDeSeleccion {

    private final Comparator<TechSpecs> ejes;

    public CriterioPorEjesTecnicos(Comparator<TechSpecs> ejes) {
        this.ejes = ejes;
    }

    @Override
    public Product elegir(List<Product> candidatos) {
        return candidatos.stream()
                .min(Comparator
                        .comparing(CriterioPorEjesTecnicos::specsDe, ejes)
                        .thenComparingDouble(Product::precio)
                        .thenComparing(CriterioPorEjesTecnicos::urlDe))
                .orElseThrow();
    }

    private static TechSpecs specsDe(Product p) {
        return TechSpecsParser.parse(p.nombre(), p.categoria());
    }

    private static String urlDe(Product p) {
        return p.url() != null ? p.url() : "";
    }
}
