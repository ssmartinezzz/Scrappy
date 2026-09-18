package ar.scraper.pcs;

import java.util.List;

/** Best-effort build result. A slot with no candidate is {@code sinStock}; one whose
 * only candidates were all vetoed is {@code sinCompatible}. Neither aborts the build. */
public record PcBuild(
        List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
        double presupuesto, double totalEstimado) {
}
