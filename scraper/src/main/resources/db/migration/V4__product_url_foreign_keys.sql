-- V4__product_url_foreign_keys.sql — normalize-db-schema-fks-1nf, slice A.1
-- (spec "db-referential-integrity", design D8/D9)
--
-- Adds the four FK relations from producto_url/url columns to productos(url)
-- that the live baseline (V1) never declared, each with the ON DELETE policy
-- fixed by prior decision:
--   precio_historico.url            -> CASCADE, VALID
--   precios_externos.producto_url   -> CASCADE, VALID
--   favoritos.url                   -> RESTRICT, NOT VALID (D8)
--   agent_reclassify_audit.url      -> no FK at all (deliberate, unchanged)
--
-- Live-data verification (obs #839) found zero orphans across all four
-- relations on the dev DB, so precio_historico/precios_externos can be added
-- VALID immediately. The orphan DELETEs below are defensive for any other
-- install this migration runs against, not corrective for this one.
--
-- favoritos is different: its rows are user data that neither this migration
-- nor a developer may delete to satisfy a constraint, and the dev DB's empty
-- favoritos table proves nothing about a real install with historical
-- orphans. `ADD FOREIGN KEY ... NOT VALID` still creates and fires the
-- referential triggers — new/updated favoritos rows are fully enforced, and
-- RESTRICT still fires on the parent DELETE. Only the one-time full-table
-- backfill check is skipped. `VALIDATE CONSTRAINT` is a deliberately deferred
-- follow-up (see design "Open Questions").

DELETE FROM precio_historico ph
    WHERE NOT EXISTS (SELECT 1 FROM productos p WHERE p.url = ph.url);

DELETE FROM precios_externos pe
    WHERE NOT EXISTS (SELECT 1 FROM productos p WHERE p.url = pe.producto_url);

ALTER TABLE precio_historico
    ADD CONSTRAINT fk_precio_historico_url
        FOREIGN KEY (url) REFERENCES productos(url) ON DELETE CASCADE;

ALTER TABLE precios_externos
    ADD CONSTRAINT fk_precios_externos_producto_url
        FOREIGN KEY (producto_url) REFERENCES productos(url) ON DELETE CASCADE;

ALTER TABLE favoritos
    ADD CONSTRAINT fk_favoritos_url
        FOREIGN KEY (url) REFERENCES productos(url) ON DELETE RESTRICT NOT VALID;

-- agent_reclassify_audit.url intentionally gets no FK — an audit trail must
-- not be bound to mutable data (same convention V2__agent_reclassify_audit.sql
-- already documents).
