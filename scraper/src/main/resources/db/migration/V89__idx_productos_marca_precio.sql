-- V89__idx_productos_marca_precio.sql
--
-- /api/data keyset mode (cursor) pages a brand by (precio, url). The brand filter is lower(coalesce(marca,'')) = ?,
-- which no index covered, so every page (first or deep) scanned all ~87k active rows: ~34 ms on the dev DB.
-- With this index the same query, now a plain equality for a single brand (CatalogQueryRepository), is an ordered index
-- range scan: 0.17 ms for page 1 and for a deep page alike (measured on a copy of the dev DB, 2026-11-10).
-- Partial on activo because every catalogue read filters by it; same shape as idx_productos_rubro_precio.
CREATE INDEX IF NOT EXISTS idx_productos_marca_precio
    ON productos (lower(coalesce(marca, '')), precio, url) WHERE activo;
