package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Ranks a slot's candidates by its technical-quality axes ({@link EjesTecnicos}), then price
 * ascending, then url ascending — price is ALWAYS the tiebreak, never the objective.
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
        // Sin presupuesto, empatados en TODO lo técnico, el más caro es la mejor pieza disponible —
        // pedido explícito del usuario (2026-09-25), sin importar la gama.
        Comparator<Product> precio = contexto.sinPresupuesto()
                ? Comparator.comparingDouble(Product::precio).reversed()
                : Comparator.comparingDouble(Product::precio);
        return candidatos.stream()
                .min(Comparator
                        .comparing(CriterioPorEjesTecnicos::specsDe, ejes)
                        .thenComparing(precio)
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
