-- V104__seed_arredo.sql — Arredo (www.arredo.com.ar), VTEX: seed plus the curated leaf mapping. 'vtex' is already a valid plataforma.
--
-- Measured 2026-11-19 (legacy API, header `resources`): 589 products; tree/3 = Ropa de cama, Baño, Cocina, Decoración,
-- Colchones y Sommiers, plus empty Packaging / Jardines Verticales / En desuso trees (fq count 0, nothing to exclude).
-- Real scrape: 589 products in 50 s. Everything is home textile, bath, kitchenware or decoration: rubro_por_defecto = 'hogar'.
-- Not rubro_forzado: a forced rubro would break the documented rollbacks of V24/V27/V45, which narrow that CHECK, and the
-- textile guard beats it anyway; NormalizerService.SITIOS_SOLO_HOGAR is what keeps the names from turning into garments.
-- Not monomarca: it sells its own line plus licensed (Disney) and collaboration ranges.
--
-- Leaf mapping: no new categories, every leaf points at an existing `categoria` row (fk_sitio_categoria_categoria).
-- Curated by hand from the leaf path and a sample of names (fq=C:<id>), not from what the classifier returns. Mixed leaves
-- (Accesorios, Bazar, Objetos Decorativos) get their dominant home category: it applies only when the name abstains.
-- Left unmapped on purpose: Batas (15, bath robes: no canonical home category) and the Infantil nodes (40, 43, 44).
-- Lookup CTEs instead of a literal per row: a string repeated three times is Sonar S1192.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Arredo', 'arredo', 'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'hogar' WHERE sitio_key = 'arredo';

WITH cat(k, categoria) AS (VALUES
    (1, 'Accesorio de Baño'),
    (2, 'Alfombra'),
    (3, 'Almohadón'),
    (4, 'Basurero'),
    (5, 'Bazar'),
    (6, 'Caja y Canasto'),
    (7, 'Colchón'),
    (8, 'Cortina'),
    (9, 'Decoración'),
    (10, 'Funda'),
    (11, 'Manta'),
    (12, 'Mantel'),
    (13, 'Mueble'),
    (14, 'Ropa de Cama'),
    (15, 'Sillón'),
    (16, 'Toalla')),
nom(k, nombre) AS (VALUES
    (1, 'Accesorios'),
    (2, 'Acolchados'),
    (3, 'Alfombras'),
    (4, 'Alfombras de bano'),
    (5, 'Almohadas'),
    (6, 'Almohadones'),
    (7, 'Bazar'),
    (8, 'Cestos de Basura'),
    (9, 'Cestos para Ropa'),
    (10, 'Colchones'),
    (11, 'Cortinas'),
    (12, 'Cortinas de bano'),
    (13, 'Cortinas roller'),
    (14, 'Cubrecamas'),
    (15, 'Cubrecolchón'),
    (16, 'Cubresommier'),
    (17, 'Esencias y Aromas'),
    (18, 'Frazadas'),
    (19, 'Fundas'),
    (20, 'Fundas de Almohadon'),
    (21, 'Individuales'),
    (22, 'Mantas'),
    (23, 'Manteles'),
    (24, 'Muebles'),
    (25, 'Objetos Decorativos'),
    (26, 'Organizadores'),
    (27, 'Repasadores'),
    (28, 'Servilletas'),
    (29, 'Sillones'),
    (30, 'Sommier y Colchón'),
    (31, 'Sábanas'),
    (32, 'Toallas y Toallones')),
hoja(origen_id, n, c) AS (VALUES
    (2, 2, 14),
    (3, 5, 14),
    (4, 20, 10),
    (5, 14, 14),
    (6, 15, 14),
    (7, 16, 14),
    (8, 18, 14),
    (10, 22, 11),
    (11, 31, 14),
    (13, 1, 1),
    (14, 4, 2),
    (16, 12, 8),
    (17, 32, 16),
    (19, 1, 5),
    (20, 21, 5),
    (21, 23, 12),
    (22, 27, 5),
    (23, 28, 5),
    (24, 7, 5),
    (26, 26, 6),
    (27, 3, 2),
    (28, 6, 3),
    (29, 8, 4),
    (30, 9, 6),
    (31, 11, 8),
    (32, 17, 9),
    (33, 24, 13),
    (37, 11, 8),
    (38, 11, 8),
    (39, 3, 2),
    (42, 29, 15),
    (56, 10, 7),
    (57, 30, 7),
    (58, 19, 10),
    (59, 13, 8),
    (60, 25, 9))
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT 'arredo', h.origen_id, nom.nombre, cat.categoria
  FROM hoja h
  JOIN nom ON nom.k = h.n
  JOIN cat ON cat.k = h.c
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
