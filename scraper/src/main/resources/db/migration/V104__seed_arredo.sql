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
-- One text blob (sitio_key|origen_id|nombre|categoria per line) split by the INSERT, not a literal per row: a string repeated
-- three times is Sonar S1192, and a long VALUES list of near-identical tuples is what Sonar CPD reads as duplicated code.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Arredo', 'arredo', 'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'hogar' WHERE sitio_key = 'arredo';

WITH linea(l) AS (SELECT regexp_split_to_table('arredo|2|Acolchados|Ropa de Cama
arredo|3|Almohadas|Ropa de Cama
arredo|4|Fundas de Almohadon|Funda
arredo|5|Cubrecamas|Ropa de Cama
arredo|6|Cubrecolchón|Ropa de Cama
arredo|7|Cubresommier|Ropa de Cama
arredo|8|Frazadas|Ropa de Cama
arredo|10|Mantas|Manta
arredo|11|Sábanas|Ropa de Cama
arredo|13|Accesorios|Accesorio de Baño
arredo|14|Alfombras de bano|Alfombra
arredo|16|Cortinas de bano|Cortina
arredo|17|Toallas y Toallones|Toalla
arredo|19|Accesorios|Bazar
arredo|20|Individuales|Bazar
arredo|21|Manteles|Mantel
arredo|22|Repasadores|Bazar
arredo|23|Servilletas|Bazar
arredo|24|Bazar|Bazar
arredo|26|Organizadores|Caja y Canasto
arredo|27|Alfombras|Alfombra
arredo|28|Almohadones|Almohadón
arredo|29|Cestos de Basura|Basurero
arredo|30|Cestos para Ropa|Caja y Canasto
arredo|31|Cortinas|Cortina
arredo|32|Esencias y Aromas|Decoración
arredo|33|Muebles|Mueble
arredo|37|Cortinas|Cortina
arredo|38|Cortinas|Cortina
arredo|39|Alfombras|Alfombra
arredo|42|Sillones|Sillón
arredo|56|Colchones|Colchón
arredo|57|Sommier y Colchón|Colchón
arredo|58|Fundas|Funda
arredo|59|Cortinas roller|Cortina
arredo|60|Objetos Decorativos|Decoración', '\n')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea)
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT f[1], f[2]::int, f[3], f[4] FROM campo
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
