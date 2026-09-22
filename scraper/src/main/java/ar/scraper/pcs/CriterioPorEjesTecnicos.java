package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Ranks a slot's candidates by its technical-quality axes ({@link
 * EjesTecnicos}), then price ascending, then url ascending — price is
 * ALWAYS the tiebreak, never the objective (pc-builder-gama T3b; replaces
 * {@code CriterioScoreMlPrecioUrl}, which ranked by {@code baseMlScore} — a
 * PRICE percentile — and so picked the cheapest candidate of any slot).
 * Every candidate arrives already filtered to the slot's own category, so
 * {@link Product#categoria()} is what {@link TechSpecsParser} reads.
 *
 * <p>Most slots' axes are fixed at construction ({@link EjesTecnicos#CPU},
 * {@code RAM}, etc). MOTHER's chipset-tier ranking is relative to the
 * requested gama (D9, T4c) — a value only known per-call, on {@link
 * ContextoDeArmado} — so the other constructor takes a {@code
 * Function<ContextoDeArmado, Comparator<TechSpecs>>} instead, built fresh
 * from {@code elegir}'s own {@code contexto} argument.</p>
 */
public class CriterioPorEjesTecnicos implements CriterioDeSeleccion {

    private final Comparator<TechSpecs> ejesFijos;
    private final Function<ContextoDeArmado, Comparator<TechSpecs>> ejesPorContexto;

    public CriterioPorEjesTecnicos(Comparator<TechSpecs> ejes) {
        this.ejesFijos = ejes;
        this.ejesPorContexto = null;
    }

    public CriterioPorEjesTecnicos(Function<ContextoDeArmado, Comparator<TechSpecs>> ejesPorContexto) {
        this.ejesFijos = null;
        this.ejesPorContexto = ejesPorContexto;
    }

    @Override
    public Product elegir(List<Product> candidatos, ContextoDeArmado contexto) {
        Comparator<TechSpecs> ejes = ejesFijos != null ? ejesFijos : ejesPorContexto.apply(contexto);
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
