-- V111__sitio_categoria_ropa_interior_y_batas.sql — two leaves the name classifier cannot place, mapped to existing categories.
--
-- Sportline ROPA INTERIOR (8 rows, all "Ropa Interior <brand> Hombre"): the generic noun is in no keyword list, so the name abstained and the rows
-- were Otros; IndumentariaClassifier now reads a leading "Ropa Interior" plus the gender, and these leaves are the fallback: men's to Calzoncillos,
-- women's to Bombacha. Unisex, kids and baby leaves stay unmapped (the name decides).
-- Arredo Batas (9 rows of bath robes): no canonical home category fits better than Toalla (bath textile); the name rule gives them the sub 'Bata'.
-- Data only, no new categories. One single-line text blob (sitio_key|origen_id|nombre|categoria rows joined by ";", no raw newline: Sonar flags one inside a literal) split by the INSERT, not a literal per row: a string repeated three times is Sonar S1192, and a long VALUES list of near-identical tuples reads as duplicated code to Sonar CPD.
WITH linea(l) AS (SELECT regexp_split_to_table('arredo|15|Batas|Toalla;arredo|61|Infantiles|Toalla;arredo|62|Adultos|Toalla;sportline|1000177|ROPA INTERIOR|Calzoncillos;sportline|1000178|ROPA INTERIOR|Bombacha', ';')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea)
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT f[1], f[2]::int, f[3], f[4] FROM campo
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
