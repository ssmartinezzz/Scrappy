package ar.scraper.pcs;

import java.util.List;
import java.util.Map;

/**
 * Best-effort build result. A slot with no candidate is {@code sinStock}; one whose only candidates
 * were all vetoed is {@code sinCompatible}.
 */
public record PcBuild(
        List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
        double presupuesto, double totalEstimado, Map<String, String> mensajes,
        Map<String, ReferenciaDeRendimiento> referencias) {

    public PcBuild(List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
            double presupuesto, double totalEstimado, Map<String, String> mensajes) {
        this(picks, sinStock, sinCompatible, presupuesto, totalEstimado, mensajes, Map.of());
    }

    public PcBuild(List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
            double presupuesto, double totalEstimado) {
        this(picks, sinStock, sinCompatible, presupuesto, totalEstimado, Map.of());
    }
}
