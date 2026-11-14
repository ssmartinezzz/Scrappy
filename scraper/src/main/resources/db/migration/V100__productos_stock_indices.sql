-- V100__productos_stock_indices.sql
--
-- Two partial indexes behind the stock filter on /api/data (page mode):
--  * idx_productos_sin_stock: the excluded set (active rows with a confirmed "no"), keyed by the check date,
--    so COUNT(...) of the rows a fresh "no" removes is an index-only scan instead of a seq scan.
--  * idx_productos_activo_precio: the default ORDER BY p.precio ASC, p.url ASC page over active rows
--    (LIMIT n reads n index entries instead of sorting the whole catalogue).
CREATE INDEX idx_productos_sin_stock
    ON productos (stock_chequeado_at)
    WHERE activo AND en_stock = false;

CREATE INDEX idx_productos_activo_precio
    ON productos (precio, url)
    WHERE activo;
