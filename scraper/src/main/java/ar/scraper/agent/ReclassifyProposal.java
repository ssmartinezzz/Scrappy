package ar.scraper.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Diff/preview returned by the {@code propose_reclassify} tool — the ONLY
 * shape a reclassification takes while still inside the agent's autonomous
 * tool-use loop (llm-catalog-nlp, design D4, Safeguard B). No write happens
 * when this is produced; it is surfaced to the user, who must explicitly
 * confirm via {@code POST /api/agent/apply} before anything is persisted.
 *
 * <p>Also the {@code @RequestBody} type of {@code POST /api/agent/apply}
 * (agent-chat-finetune WU4 — the client already posts this shape verbatim,
 * see {@code frontend/src/api.js}'s {@code applyProposal}; the endpoint used
 * to read mismatched Map keys and 400 on every real click).
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} lets a retried
 * proposal carry the UI-only {@code _applied}/{@code _mensaje} keys
 * {@code AgentChatPanel.jsx} adds to track confirm/reject state without
 * failing binding — a typed body is never itself evidence the client
 * validated anything, so the endpoint still re-validates every field
 * server-side.</p>
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
