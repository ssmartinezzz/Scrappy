-- V140__rubro_celulares.sql
--
-- New rubro 'celulares' (orden 12, USD_OFICIAL, peso_forzado 0, peso_categoria 0), as rows of the V138 tables.
--   categoria_rubro  ('Celular', '') moves from tecnologia to celulares. peso_categoria 0 is what tecnologia had as a category, so a
--                    forced site (tecnologia weighs 2 as a forced rubro, oficina 1) still keeps its own phones: the move only reaches the
--                    stores with no forced rubro. The price index is the dollar, as for tecnologia: a phone is priced in USD.
--   Batería Externa  a new sub of 'Celular', carved out of 'Cargador': a charger that is a power bank (SubcategoryResolver.POWER_BANK,
--                    the same regex as below). A phone charger of any other kind keeps 'Cargador'.
-- The stored productos, active and inactive, end where RubroResolver puts them; a row locked by hand (bloqueado_por) is not touched:
--   * 'Celular' on a site with no forced rubro: celulares (a forced site keeps its rubro);
--   * 'Celular' / 'Cargador' whose name is a power bank: 'Batería Externa'.
-- The chargers, power banks and phone holders that sit in 'Otros' today are not moved here: AccesorioDeTelefonoClassifier gives them 'Celular' on the
-- next scrape or renormalizar, and the rubro follows from the category.
-- The rubro is looked up by its orden, not repeated as a literal (Sonar S1192). The name is read as TextoClasificable reads it: lower case,
-- the accents of AccentStripper removed, one space on each side, punctuation untouched.
-- Re-runnable. The V40 trigger bumps catalog_version once at COMMIT however many rows change. No ALTER TABLE on productos, so the
-- SET CONSTRAINTS flush of V112 is not needed.
INSERT INTO rubro (clave, etiqueta, orden, indice, peso_forzado, peso_categoria)
VALUES ('celulares', 'Celulares', 12, 'USD_OFICIAL', 0, 0)
ON CONFLICT (clave) DO NOTHING;

UPDATE categoria_rubro
   SET rubro = (SELECT clave FROM rubro WHERE orden = 12)
 WHERE sub_categoria = ''
   AND categoria = 'Celular';

UPDATE productos p
   SET rubro = cr.rubro
  FROM categoria_rubro cr, sitio s, rubro e
 WHERE e.orden = 12
   AND cr.rubro = e.clave
   AND cr.sub_categoria = ''
   AND p.categoria = cr.categoria
   AND s.sitio_key = p.sitio_key
   AND COALESCE(s.rubro_forzado, '') = ''
   AND p.rubro IS DISTINCT FROM cr.rubro
   AND p.bloqueado_por IS NULL;

UPDATE productos
   SET sub_categoria = 'Batería Externa'
 WHERE categoria = 'Celular'
   AND sub_categoria = 'Cargador'
   AND bloqueado_por IS NULL
   AND (' ' || translate(lower(nombre), 'áàäéèëíìïóòöúùüñ', 'aaaeeeiiiooouuun') || ' ')
       ~ ' (?:power ?banks?|baterias? externas?|bateria portatil|external batter(?:y|ies)|portable charger)(?![a-z0-9])| cargador(?:es)? portatil(?![a-z0-9])|[0-9] ?mah(?![a-z0-9])';
