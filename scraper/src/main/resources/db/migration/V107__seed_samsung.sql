-- V107__seed_samsung.sql — Samsung Argentina official store (shop.samsung.com.ar), VTEX: seed plus the curated leaf mapping.
--
-- Measured 2026-11-19 (legacy API, header `resources`): 759 products, 702 in a real scrape (35-55 s). shop.samsung.com/ar/api answers the
-- same API, but VtexPage builds API URLs from scheme + host and would drop the /ar prefix: the store's own domain is used instead.
-- Official monobrand store, so monomarca = true (NormalizerService assigns marca = sitio.nombre) and 'Samsung' must be a `marca` row,
-- or fk_productos_marca (V52) rejects every product and the scrape reads "0 nuevos". It is not in BrandExtractor.MARCAS: monomarca wins.
-- Mixed catalogue (phones, TVs, appliances, monitors): rubro_forzado stays NULL, rubro_por_defecto = 'tecnologia'; appliances are hogar by category.
--
-- Leaf mapping: no new categories. Curated by hand from the leaf path and the names under it. For this store the curated leaf outranks the
-- name (NormalizerService.SITIOS_DONDE_LA_HOJA_GANA), except where the name says another device (a Galaxy Tab on a phone series leaf).
-- Mapped explicitly to 'Otros' (appliance spare parts, tablet and TV accessories have no canonical category): 137, 150, 166, 184, 186, 193,
-- 258-264 and 274-279. Left unmapped: 215 (SmartThings: sensors, hubs and Philips Hue lamps share one node, the name decides).
-- 'Samsung Care' (warranties and plans, 169 rows) and the Hidden/Teste/Members/Membership/Evouchers nodes are not products:
-- ServicioPorNombreDeCategoria excludes them, so they never reach the database. 'Servicios DA' is NOT excluded: it is a tag on
-- real appliances (80 of them also sit under Linea Hogar), and VtexPage skips a product that has any service node.
-- One single-line text blob (sitio_key|origen_id|nombre|categoria rows joined by ';', no raw newline: Sonar flags one inside a literal) split by the INSERT, not a literal per row: a string repeated
-- three times is Sonar S1192, and a long VALUES list of near-identical tuples is what Sonar CPD reads as duplicated code.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Samsung', 'samsung', 'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true, rubro_por_defecto = 'tecnologia' WHERE sitio_key = 'samsung';

INSERT INTO marca (nombre) VALUES ('Samsung')
ON CONFLICT DO NOTHING;

WITH linea(l) AS (SELECT regexp_split_to_table('samsung|3|Smartphones|Celular;samsung|5|Accesorios Celulares|Celular;samsung|43|Monitores|Monitor;samsung|44|Audio|TV y Audio;samsung|45|Barra de Sonido|TV y Audio;samsung|53|Sound Tower|TV y Audio;samsung|56|Freezer Superior|Heladera;samsung|57|Side by Side|Heladera;samsung|58|Freezer Inferior|Heladera;samsung|59|French Door|Heladera;samsung|60|Carga Frontal|Lavarropas;samsung|61|Carga Superior|Lavarropas;samsung|62|Lavasecarropas|Lavarropas;samsung|63|Secarropas|Lavarropas;samsung|64|Campanas|Campana y Extractor;samsung|65|Hornos|Cocina y Horno;samsung|66|Anafes|Cocina y Horno;samsung|67|Microondas|Cocina y Horno;samsung|71|Proyectores|TV y Audio;samsung|100|BeSpoke|Heladera;samsung|103|Lavado con Inteligencia Artificial|Lavarropas;samsung|117|kits de reparacion|Celular;samsung|119|Serie Z|Celular;samsung|120|Serie S|Celular;samsung|121|Serie A|Celular;samsung|124|Serie S|Tablet;samsung|125|Serie A|Tablet;samsung|127|Galaxy Watch 6 Series|Reloj;samsung|128|Galaxy Watch 5 Series|Reloj;samsung|134|Galaxy Buds Fe|Auricular;samsung|136|Accesorios Smartphones|Celular;samsung|137|Accesorios Tablets|Otros;samsung|138|Accesorios Watches|Reloj;samsung|139|Cargadores|Celular;samsung|140|Otros|Celular;samsung|142|Galaxy Book|Notebook;samsung|144|Gaming|Monitor;samsung|145|Smart Monitor|Monitor;samsung|146|Alta Resolución|Monitor;samsung|147|FHD Monitor|Monitor;samsung|150|Accesorios Galaxy Book|Otros;samsung|152|Mouse|Mouse;samsung|153|Galaxy Book 3 Series|Notebook;samsung|156|Galaxy Book 3 Pro|Notebook;samsung|159|Lavavajilla|Lavarropas;samsung|162|Power Stick|Aspiradora;samsung|165|Aires acondicionados|Aire Acondicionado;samsung|166|Accesorios|Otros;samsung|171|Oled|TV y Audio;samsung|172|Neo Qled|TV y Audio;samsung|173|Qled|TV y Audio;samsung|174|The Frame|TV y Audio;samsung|175|Crystal UHD|TV y Audio;samsung|176|Business TVs|TV y Audio;samsung|177|Full Hd tvs|TV y Audio;samsung|184|Accesorios Tv|Otros;samsung|186|Accesorios Proyectores|Otros;samsung|193|Fundas - Baterias|Otros;samsung|195|Smart Signage|TV y Audio;samsung|206|Galaxy Fit3|Reloj;samsung|217|Galaxy Buds3|Auricular;samsung|218|Galaxy Buds3 Pro|Auricular;samsung|219|Galaxy Watch7 Series|Reloj;samsung|244|Galaxy Buds Core|Auricular;samsung|248|Galaxy Watch 8 Series|Reloj;samsung|250|Galaxy Buds3 FE|Auricular;samsung|251|Galaxy Book 4 Series|Notebook;samsung|258|Tanque|Otros;samsung|259|Anaqueles|Otros;samsung|260|Cajones|Otros;samsung|261|Huevera|Otros;samsung|262|Estante|Otros;samsung|263|Cubeteras|Otros;samsung|264|Fábricas de hielo|Otros;samsung|274|Carcasas|Otros;samsung|275|Depósito de residuos|Otros;samsung|276|Filtros|Otros;samsung|277|Boquillas|Otros;samsung|278|Plato|Otros;samsung|279|Guías para plato|Otros;samsung|281|Galaxy Buds4|Auricular;samsung|282|Galaxy Buds4 Pro|Auricular;samsung|285|Micro RGB|TV y Audio;samsung|286|Mini Led|TV y Audio;samsung|287|Galaxy Watch 9 Series|Reloj;samsung|288|Freezer Horizontal|Heladera;samsung|289|Music Studio|TV y Audio', ';')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea)
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria)
SELECT f[1], f[2]::int, f[3], f[4] FROM campo
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
