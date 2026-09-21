package ar.scraper.pcs;

import java.util.List;
import java.util.Map;

/**
 * Best-effort build result. A slot with no candidate is {@code sinStock}; one whose
 * only candidates were all vetoed is {@code sinCompatible}. Neither aborts the build.
 *
 * <p>{@code mensajes} (pc-builder-gama T3b-2, D6) carries one entry per EMPTY slot
 * only — a slot with a pick has no entry. {@code sinStock} names the empty category;
 * {@code sinCompatible} joins the distinct {@link ar.scraper.pcs.reglas.ReglaCompatibilidad#motivo()}
 * of whichever rules actually vetoed a candidate, in the slot's own rule order.</p>
 */
public record PcBuild(
        List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
        double presupuesto, double totalEstimado, Map<String, String> mensajes) {

    /**
     * Pre-T3b-2 shape, kept so callers that never built a mensajes map
     * (PcBuildJsonTest) keep compiling untouched — refactor contract, CODE-2.
     */
    public PcBuild(List<PcPick> picks, List<String> sinStock, List<String> sinCompatible,
            double presupuesto, double totalEstimado) {
        this(picks, sinStock, sinCompatible, presupuesto, totalEstimado, Map.of());
    }
}
