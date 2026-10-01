package ar.scraper.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Diff/preview returned by the {@code propose_reclassify} tool — the ONLY shape a reclassification
 * takes while still inside the agent's autonomous tool-use loop.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReclassifyProposal(
        String url,
        String nombreProducto,
        String categoriaActual,
        String categoriaPropuesta,
        String subCategoriaPropuesta,
        String marcaPropuesta,
        String generoPropuesto
) {}
