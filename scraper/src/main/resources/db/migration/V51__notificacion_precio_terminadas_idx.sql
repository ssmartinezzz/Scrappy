-- V51__notificacion_precio_terminadas_idx.sql — serves the retention purge
--
-- The purge deletes ENVIADA/FALLIDA rows by COALESCE(enviada_at, proximo_intento_at),
-- which no existing index can answer: without this it scans the whole table to find
-- the old tail. Partial on the two terminal states, so pending rows (the hot ones)
-- never touch it. Full rationale and rollback: docs/DATABASE.md.

CREATE INDEX idx_notificacion_precio_terminadas
    ON notificacion_precio ((COALESCE(enviada_at, proximo_intento_at)))
    WHERE estado IN ('ENVIADA', 'FALLIDA');
