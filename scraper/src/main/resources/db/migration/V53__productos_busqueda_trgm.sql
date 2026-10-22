-- V53__productos_busqueda_trgm.sql
--
-- The catalog search filter is (nombre ILIKE '%q%' OR marca ILIKE '%q%'). A btree
-- cannot serve a leading wildcard, so both the count and the page-urls query
-- seq-scanned the whole catalog. A trigram GIN index answers ILIKE '%q%' for q of
-- 3+ characters; shorter terms still fall back to the scan.
--
-- pg_trgm is a trusted extension since PG13, so no superuser is needed.
-- Not CONCURRENTLY: Flyway runs each migration in a transaction.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_productos_nombre_trgm ON productos USING gin (nombre gin_trgm_ops);
CREATE INDEX idx_productos_marca_trgm ON productos USING gin (marca gin_trgm_ops);
