-- V136__merge_tiendanube_entry_twins.sql
--
-- A Tiendanube theme appends where the shopper came from (entry_path, entry_category_id) to the links of a
-- category listing, and productos.url is the identity, so one product was stored once per category it appeared
-- in. The scraper now drops those two params (TiendanubeUrl); this folds the rows already stored into the
-- canonical url. Only rows whose query carries one of them are candidates; a product's canonical row is created
-- when missing and updated when present.
--
-- NOT reversible from data: the twin rows are deleted and their per-category identity is gone. The way back is
-- the pg_dump taken before applying it (docs/DATABASE.md).
--
-- Plain DML on purpose: the deferred catalog_version triggers make any ALTER on these tables fail while their
-- events are pending (see V112), so nothing here is DDL on a table with a pending trigger.

-- canonical url of every candidate; must agree with TiendanubeUrl.canonica (a test runs both on the same inputs)
CREATE TEMP TABLE tmp_identidad_twin ON COMMIT DROP AS
WITH partido AS (
    SELECT url,
           CASE WHEN position('#' IN url) > 0 THEN substr(url, position('#' IN url)) ELSE '' END AS frag,
           CASE WHEN position('#' IN url) > 0 THEN left(url, position('#' IN url) - 1) ELSE url END AS cabeza
    FROM productos
    WHERE url ~ 'entry_(path|category_id)'
),
con_query AS (
    SELECT url, frag, cabeza, position('?' IN cabeza) AS q FROM partido WHERE position('?' IN cabeza) > 0
),
reescrita AS (
    SELECT url, frag, left(cabeza, q - 1) AS base,
           (SELECT coalesce(string_agg(s.seg, '&' ORDER BY s.ord), '')
              FROM unnest(string_to_array(substr(cabeza, q + 1), '&')) WITH ORDINALITY AS s(seg, ord)
             WHERE split_part(s.seg, '=', 1) <> ALL (ARRAY['entry_path', 'entry_category_id'])) AS query
    FROM con_query
)
SELECT url, base || CASE WHEN query <> '' THEN '?' || query ELSE '' END || frag AS canon
FROM reescrita
WHERE base || CASE WHEN query <> '' THEN '?' || query ELSE '' END || frag <> url;

-- every row of a group: the twins plus the canonical row when it already exists. rank 1 is the winner:
-- active first, then the most recently touched, then the url so a tie is deterministic.
CREATE TEMP TABLE tmp_identidad_miembro ON COMMIT DROP AS
SELECT u.url, u.canon,
       row_number() OVER (PARTITION BY u.canon
                          ORDER BY (p.activo IS NOT FALSE) DESC, p.touched_at DESC NULLS LAST, u.url) AS rango
FROM (SELECT url, canon FROM tmp_identidad_twin
      UNION
      SELECT p.url, p.url FROM productos p WHERE p.url IN (SELECT canon FROM tmp_identidad_twin)) u
JOIN productos p ON p.url = u.url;

CREATE TEMP TABLE tmp_identidad_grupo ON COMMIT DROP AS
SELECT m.canon,
       max(m.url) FILTER (WHERE m.rango = 1) AS ganador,
       -- the lock travels with the classification it protects, so it comes from one row: the latest lock
       (array_agg(m.url ORDER BY p.bloqueado_at DESC NULLS LAST, m.rango)
            FILTER (WHERE p.bloqueado_por IS NOT NULL))[1] AS bloqueo,
       min(p.created_at) AS created_at,
       bool_or(p.activo IS NOT FALSE) AS activo,
       max(p.visto_en_stock_at) AS visto_en_stock_at
FROM tmp_identidad_miembro m
JOIN productos p ON p.url = m.url
GROUP BY m.canon;

-- producto_key and sitio_key are generated: not inserted
INSERT INTO productos (url, sitio, nombre, precio, precio_orig, imagen_url, categoria, genero, ml_score, ml_oferta,
                       ml_tendencia, ml_segment, ml_zscore, rubro, marca, gymrat, cantidad_unidades, sub_categoria,
                       fit, estampado, escote, color_dominante, activo, touched_at, created_at, bloqueado_por,
                       bloqueado_at, deporte, sitio_categoria_id, en_stock, visto_en_stock_at, stock_chequeado_at)
SELECT g.canon, w.sitio, w.nombre, w.precio, w.precio_orig, w.imagen_url, l.categoria, l.genero, w.ml_score,
       w.ml_oferta, w.ml_tendencia, w.ml_segment, w.ml_zscore, l.rubro, l.marca, w.gymrat, w.cantidad_unidades,
       l.sub_categoria, w.fit, w.estampado, w.escote, w.color_dominante, g.activo, w.touched_at, g.created_at,
       l.bloqueado_por, l.bloqueado_at, w.deporte, w.sitio_categoria_id, w.en_stock, g.visto_en_stock_at,
       w.stock_chequeado_at
FROM tmp_identidad_grupo g
JOIN productos w ON w.url = g.ganador
JOIN productos l ON l.url = coalesce(g.bloqueo, g.ganador)
ON CONFLICT (url) DO UPDATE SET
    sitio = EXCLUDED.sitio, nombre = EXCLUDED.nombre, precio = EXCLUDED.precio, precio_orig = EXCLUDED.precio_orig,
    imagen_url = EXCLUDED.imagen_url, categoria = EXCLUDED.categoria, genero = EXCLUDED.genero,
    ml_score = EXCLUDED.ml_score, ml_oferta = EXCLUDED.ml_oferta, ml_tendencia = EXCLUDED.ml_tendencia,
    ml_segment = EXCLUDED.ml_segment, ml_zscore = EXCLUDED.ml_zscore, rubro = EXCLUDED.rubro, marca = EXCLUDED.marca,
    gymrat = EXCLUDED.gymrat, cantidad_unidades = EXCLUDED.cantidad_unidades,
    sub_categoria = EXCLUDED.sub_categoria, fit = EXCLUDED.fit, estampado = EXCLUDED.estampado,
    escote = EXCLUDED.escote, color_dominante = EXCLUDED.color_dominante, activo = EXCLUDED.activo,
    touched_at = EXCLUDED.touched_at, created_at = EXCLUDED.created_at, bloqueado_por = EXCLUDED.bloqueado_por,
    bloqueado_at = EXCLUDED.bloqueado_at, deporte = EXCLUDED.deporte,
    sitio_categoria_id = EXCLUDED.sitio_categoria_id, en_stock = EXCLUDED.en_stock,
    visto_en_stock_at = EXCLUDED.visto_en_stock_at, stock_chequeado_at = EXCLUDED.stock_chequeado_at;

-- price history: one price per (url, fecha), the best-ranked member that has that date; no date is lost
INSERT INTO precio_historico (url, precio, fecha)
SELECT DISTINCT ON (m.canon, h.fecha) m.canon, h.precio, h.fecha
FROM tmp_identidad_miembro m
JOIN precio_historico h ON h.url = m.url
ORDER BY m.canon, h.fecha, m.rango
ON CONFLICT (url, fecha) DO UPDATE SET precio = EXCLUDED.precio;

UPDATE precios_externos e SET producto_url = m.canon
FROM tmp_identidad_miembro m WHERE e.producto_url = m.url AND m.url <> m.canon;

-- sizes and badges are position-keyed lists: the winner's whole list replaces the canonical row's
DELETE FROM producto_talle t USING tmp_identidad_grupo g WHERE t.url = g.canon AND g.ganador <> g.canon;
UPDATE producto_talle t SET url = g.canon FROM tmp_identidad_grupo g WHERE t.url = g.ganador AND g.ganador <> g.canon;
DELETE FROM producto_badge b USING tmp_identidad_grupo g WHERE b.url = g.canon AND g.ganador <> g.canon;
UPDATE producto_badge b SET url = g.canon FROM tmp_identidad_grupo g WHERE b.url = g.ganador AND g.ganador <> g.canon;

-- tech specs: one row per product, from the best-ranked member that has one
CREATE TEMP TABLE tmp_identidad_specs ON COMMIT DROP AS
SELECT DISTINCT ON (m.canon) m.canon, m.url AS origen
FROM tmp_identidad_miembro m
JOIN producto_tech_specs s ON s.url = m.url
ORDER BY m.canon, m.rango;
DELETE FROM producto_tech_specs s USING tmp_identidad_specs x WHERE s.url = x.canon AND x.origen <> x.canon;
UPDATE producto_tech_specs s SET url = x.canon FROM tmp_identidad_specs x WHERE s.url = x.origen AND x.origen <> x.canon;

-- favoritos: one per (user, product), the earliest added; a favorite with no owner is its own partition
DELETE FROM favoritos f USING (
    SELECT f2.id, row_number() OVER (PARTITION BY f2.usuario_id, m.canon
                                     ORDER BY f2.added_at NULLS LAST, f2.id) AS n
    FROM favoritos f2 JOIN tmp_identidad_miembro m ON m.url = f2.url) d
WHERE f.id = d.id AND d.n > 1;
UPDATE favoritos f SET url = m.canon FROM tmp_identidad_miembro m WHERE f.url = m.url AND m.url <> m.canon;

-- price alerts: one per (user, product, new price); a sent one wins so it is not sent again
DELETE FROM notificacion_precio n USING (
    SELECT n2.id, row_number() OVER (PARTITION BY n2.usuario_id, m.canon, n2.precio_nuevo
                                     ORDER BY CASE n2.estado WHEN 'ENVIADA' THEN 0 WHEN 'PENDIENTE' THEN 1 ELSE 2 END,
                                              n2.creada_at, n2.id) AS n
    FROM notificacion_precio n2 JOIN tmp_identidad_miembro m ON m.url = n2.url) d
WHERE n.id = d.id AND d.n > 1;
UPDATE notificacion_precio n SET url = m.canon FROM tmp_identidad_miembro m WHERE n.url = m.url AND m.url <> m.canon;

INSERT INTO promo_financiacion_producto (promo_id, url, visto_at)
SELECT p.promo_id, m.canon, max(p.visto_at)
FROM tmp_identidad_miembro m
JOIN promo_financiacion_producto p ON p.url = m.url
GROUP BY p.promo_id, m.canon
ON CONFLICT (promo_id, url) DO UPDATE
    SET visto_at = GREATEST(promo_financiacion_producto.visto_at, EXCLUDED.visto_at);

-- tables that hold a url with no foreign key: the latest embedding survives, the rest are repointed
CREATE TEMP TABLE tmp_identidad_embedding ON COMMIT DROP AS
SELECT DISTINCT ON (m.canon) m.canon, m.url AS origen
FROM tmp_identidad_miembro m
JOIN image_embeddings e ON e.url = m.url
ORDER BY m.canon, e.computed_at DESC, m.rango;
DELETE FROM image_embeddings e USING tmp_identidad_embedding x WHERE e.url = x.canon AND x.origen <> x.canon;
UPDATE image_embeddings e SET url = x.canon FROM tmp_identidad_embedding x WHERE e.url = x.origen AND x.origen <> x.canon;
DELETE FROM image_embeddings WHERE url IN (SELECT url FROM tmp_identidad_miembro WHERE url <> canon);

UPDATE saved_outfit_item i SET url = m.canon FROM tmp_identidad_miembro m WHERE i.url = m.url AND m.url <> m.canon;
UPDATE saved_pc_item i SET url = m.canon FROM tmp_identidad_miembro m WHERE i.url = m.url AND m.url <> m.canon;
UPDATE outfit_feedback_item i SET url = m.canon FROM tmp_identidad_miembro m WHERE i.url = m.url AND m.url <> m.canon;
UPDATE agent_reclassify_audit a SET url = m.canon FROM tmp_identidad_miembro m WHERE a.url = m.url AND m.url <> m.canon;

-- whatever still hangs off a twin (history already copied, unchosen lists, losing specs) goes with it
DELETE FROM productos WHERE url IN (SELECT url FROM tmp_identidad_miembro WHERE url <> canon);
