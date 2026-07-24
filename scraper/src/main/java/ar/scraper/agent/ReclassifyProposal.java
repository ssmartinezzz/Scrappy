package ar.scraper.agent;

/**
 * Diff/preview returned by the {@code propose_reclassify} tool — the ONLY
 * shape a reclassification takes while still inside the agent's autonomous
 * tool-use loop (llm-catalog-nlp, design D4, Safeguard B). No write happens
 * when this is produced; it is surfaced to the user, who must explicitly
 * confirm via {@code POST /api/agent/apply} before anything is persisted.
 */
public record ReclassifyProposal(
        String url,
        String nombreProducto,
        String categoriaActual,
        String categoriaPropuesta,
        String subCategoriaPropuesta,
        String marcaPropuesta,
        String generoPropuesto
) {}
