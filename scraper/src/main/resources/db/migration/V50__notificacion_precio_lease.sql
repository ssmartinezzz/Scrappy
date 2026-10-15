-- V50__notificacion_precio_lease.sql — tells a live lease from a backoff
--
-- proximo_intento_at is both "when to retry" and "when the lease ends", so a
-- row waiting out a backoff looks exactly like a row a relay is sending right
-- now. The cluster-wide mail cap needs the second kind only: lease_hasta is
-- set when a relay claims the row and cleared when it marks it. Full
-- rationale and rollback: docs/DATABASE.md.

ALTER TABLE notificacion_precio
    ADD COLUMN lease_hasta TIMESTAMPTZ;

CREATE INDEX idx_notificacion_precio_en_vuelo
    ON notificacion_precio (lease_hasta)
    WHERE lease_hasta IS NOT NULL;
