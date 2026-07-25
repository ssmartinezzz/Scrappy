-- V2__agent_reclassify_audit.sql — agent-chat-finetune (LLM catalog agent
-- confirm-button fix, WU1)
--
-- Audit trail for POST /api/agent/apply: one row per successful human-confirmed
-- reclassification, written atomically with the productos UPDATE inside
-- DatabaseService#aplicarReclasificacionAuditada. If the INSERT below fails,
-- the whole transaction rolls back — there is never an audit row for a
-- reclassification that didn't happen, and never a reclassification without
-- one. No foreign key on `url` (same convention as the rest of this baseline —
-- see V1__baseline.sql header — the schema has exactly one FK today).

CREATE TABLE agent_reclassify_audit (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    url                   TEXT NOT NULL,
    categoria_antes       TEXT,
    categoria_despues     TEXT,
    marca_antes           TEXT,
    marca_despues         TEXT,
    genero_antes          TEXT,
    genero_despues        TEXT,
    sub_categoria_antes   TEXT,
    sub_categoria_despues TEXT,
    applied_at            TEXT NOT NULL
);
CREATE INDEX idx_agent_reclassify_audit_url ON agent_reclassify_audit(url);
