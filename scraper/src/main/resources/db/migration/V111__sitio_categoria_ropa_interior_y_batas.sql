-- V111__sitio_categoria_ropa_interior_y_batas.sql — two leaves the name classifier cannot place, mapped to existing categories.
--
-- Sportline ROPA INTERIOR (8 rows, all "Ropa Interior <brand> Hombre"): the generic noun is in no keyword list, so the name abstained and the rows
-- were Otros; IndumentariaClassifier now reads a leading "Ropa Interior" plus the gender, and these leaves are the fallback: men's to Calzoncillos,
-- women's to Bombacha. Unisex, kids and baby leaves stay unmapped (the name decides).
-- Arredo Batas (9 rows of bath robes): no canonical home category fits better than Toalla (bath textile); the name rule gives them the sub 'Bata'.
-- Data only, no new categories. Lookup CTEs instead of a literal per row: a string repeated three times is Sonar S1192.
WITH sitio_k(k, sitio_key) AS (VALUES
    (1, 'arredo'),
    (2, 'sportline')),
cat(k, categoria) AS (VALUES
    (1, 'Toalla'),
    (2, 'Calzoncillos'),
    (3, 'Bombacha')),
nom(k, nombre) AS (VALUES
    (1, 'Batas'),
    (2, 'Infantiles'),
    (3, 'Adultos'),
    (4, 'ROPA INTERIOR')),
hoja(s, origen_id, n, c) AS (VALUES
    (1, 15, 1, 1),
    (1, 61, 2, 1),
    (1, 62, 3, 1),
    (2, 1000177, 4, 2),
    (2, 1000178, 4, 3))
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT sk.sitio_key, h.origen_id, nom.nombre, cat.categoria
  FROM hoja h
  JOIN sitio_k sk ON sk.k = h.s
  JOIN nom ON nom.k = h.n
  JOIN cat ON cat.k = h.c
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
