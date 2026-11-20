-- V123__seed_moov.sql: Moov, Salesforce Commerce Cloud: seed plus the curated leaf mapping of its accessories.
--
-- Moov (www.moov.com.ar), same group and same SFCC storefront as Dexter (V121, V122), more urban than sporty: sneakers, apparel, bags, caps and socks of Nike, adidas, Puma, Converse, Fila.
-- Measured 2026-11-20 (Search-UpdateGrid, cgid=root, ordered by price): 3614 products announced, 3614 distinct tiles read in a real scrape (39 s). 3440 tiles (95%) carry a taxonomy leaf;
-- the origin ids are the same hashes as Dexter's, the rows are per site.
-- rubro_forzado and rubro_por_defecto stay NULL (RubroResolver decides by category), like Sportline and Dexter.
--
-- Leaf mapping: no new categories. Only the accessory leaves the name classifier abstains on or gets wrong (a pack of socks read as Conjunto, laces and cleaning kits as Otros); apparel and
-- footwear leaves stay unmapped so the name keeps Zapatilla Urbana vs Sneaker. The curated leaf outranks the name (NormalizerService.SITIOS_DONDE_LA_HOJA_GANA).
-- One single-line text blob, same shape as V114 and V122.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Moov', 'moov', 'sfcc', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

WITH linea(l) AS (SELECT regexp_split_to_table('moov|2033630368|Billeteras|Billetera;moov|693857283|Bolsos|Bolso;moov|1292929019|Botellas|Termo y Botella;moov|1542265362|Botineros|Bolso;moov|1304497802|Carteras|Bolso;moov|830165302|Cordones|Accesorio Deportivo;moov|1087341804|Cuellos|Bufanda;moov|837180389|Gorras|Gorra;moov|837180823|Gorros|Gorro;moov|338762108|Guantes|Guantes;moov|1702271122|Limpiadores|Artículo de Limpieza;moov|1085303985|MangasLanzador|Equipamiento Deportivo;moov|999294362|Medias|Medias;moov|652475495|Mochilas|Mochila;moov|1090909766|Morrales|Bolso;moov|1569900269|Munequeras|Accesorio Deportivo;moov|734286388|Packdemedias|Medias;moov|797144856|Pines|Bijouterie;moov|1369416203|Portacelulares|Accesorio Deportivo;moov|1214131061|Protectores|Accesorio Deportivo;moov|2110040938|Rinoneras|Riñonera;moov|579989404|Sets|Accesorio de Fitness;moov|1359551808|Setsdelimpieza|Artículo de Limpieza;moov|802452769|Vasos|Termo y Botella;moov|434532039|Vinchas|Vincha;moov|1429711639|Ballerinas|Zapato;moov|577132427|Remerones|Remera', ';')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea)
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT f[1], f[2]::int, f[3], f[4] FROM campo
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
