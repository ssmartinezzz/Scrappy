-- V93__categorias_desodorante_y_llavero.sql
--
-- 1. New categories 'Desodorante' (KW_DESODORANTE, rubro perfumeria) and 'Llavero' (KW_LLAVERO, in no rubro group).
-- A new category is TWO changes: the classifier produces it, and these rows let productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without them every upsert of such a product violates the FK,
-- ProductRepository swallows the error and the symptom is "0 nuevos".
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Desodorante'), ('Llavero')
ON CONFLICT (nombre) DO NOTHING;

-- 2. Stored deodorants were filed as Perfume (KW_PERFUME used to hold their words). Rubro is set here because
-- renormalizar does not persist it (V90). Never a row locked by hand (V3). Idempotent: a moved row is no longer 'Perfume'.
UPDATE productos
   SET categoria = 'Desodorante', rubro = 'perfumeria'
 WHERE categoria = 'Perfume'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
        ~ '(desodorante |antitranspirante)';

-- 3. Perfume rows whose name carries no fragrance word were filed before KW_PERFUME was tightened (V90 era: bare
-- "splash", "colonia" as a substring). renormalizar feeds the stored categoria back as raw, so they stay Perfume until
-- a rescrape: realign them by hand. "scent" is a fragrance word that is not in KW_PERFUME (Freres "Scent"). The rubro is
-- left alone: the site's own rubro is already right for these rows.
UPDATE productos
   SET categoria = CASE
         WHEN n ~ 'antiparra' THEN 'Lentes'
         WHEN n ~ '(inflable|tobogan|bracitos)' THEN 'Inflable'
         WHEN rubro = 'juguetes' THEN 'Juguete'
         ELSE 'Otros'
       END
  FROM (SELECT url, (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') AS n FROM productos WHERE categoria = 'Perfume') q
 WHERE productos.url = q.url
   AND productos.categoria = 'Perfume'
   AND productos.bloqueado_por IS NULL
   AND q.n !~ '(perfume| colonia |eau de toilette|eau de parfum|fragancia|body splash|body mist| scent )';
