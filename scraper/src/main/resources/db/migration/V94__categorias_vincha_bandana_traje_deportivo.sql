-- V94__categorias_vincha_bandana_traje_deportivo.sql
--
-- 1. New categories 'Vincha', 'Bandana' and 'Traje Deportivo' (all indumentaria; KW_VINCHA, KW_BANDANA, KW_TRAJE_DEPORTIVO).
-- A new category is TWO changes: the classifier produces it, and these rows let productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without them every upsert of such a product violates the FK,
-- ProductRepository swallows the error and the symptom is "0 nuevos".
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Vincha'), ('Bandana'), ('Traje Deportivo')
ON CONFLICT (nombre) DO NOTHING;

-- renormalizar feeds the stored categoria back as raw, so a keyword fix alone never moves a stored row:
-- the UPDATEs below do it. Names are space-padded and accent-folded like TextoClasificable.normalizado.
-- Never a row locked by hand (V3). Each UPDATE is idempotent: a moved row no longer matches its WHERE.

-- 2. Vincha and bandana were Gorra. The vincha wins when a name has both ("Vincha Wilson Bandana").
UPDATE productos
   SET categoria = 'Vincha'
 WHERE categoria = 'Gorra'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' vincha';

UPDATE productos
   SET categoria = 'Bandana'
 WHERE categoria = 'Gorra'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' bandanas? ';

-- 3. Tracksuits and catsuits. A loose piece ("CAMPERA TRACKSUIT NEGRA") goes back to its leading noun first;
-- what is left in Traje / Conjunto is the one-piece suit or the set.
UPDATE productos
   SET categoria = CASE
         WHEN n ~ '^ campera'   THEN 'Campera'
         WHEN n ~ '^ pantalon'  THEN 'Pantalón'
         WHEN n ~ '^ buzo'      THEN 'Buzo'
         WHEN n ~ '^ remera'    THEN 'Remera'
         WHEN n ~ '^ short '    THEN 'Short'
         WHEN n ~ '^ chaleco'   THEN 'Chaleco'
         WHEN n ~ '^ jogg'      THEN 'Jogging'
         WHEN n ~ '^ calza'     THEN 'Calza'
         WHEN n ~ '^ musculosa' THEN 'Musculosa'
         WHEN n ~ '^ bermuda'   THEN 'Bermuda'
         WHEN n ~ '^ sweater'   THEN 'Sweater'
         WHEN n ~ '^ top '      THEN 'Top'
         WHEN n ~ '^ camiseta'  THEN 'Remera'
         ELSE 'Chomba'
       END,
       sub_categoria = ''
  FROM (SELECT url, (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') AS n FROM productos) q
 WHERE productos.url = q.url
   AND productos.categoria = 'Traje'
   AND productos.bloqueado_por IS NULL
   AND q.n ~ ' (tracksuit|catsuit)'
   AND q.n ~ '^ (campera|pantalon|buzo|remera|short |chaleco|jogg|calza|musculosa|bermuda|sweater|top |camiseta|chomba)';

UPDATE productos
   SET categoria = 'Traje Deportivo', sub_categoria = ''
 WHERE categoria IN ('Traje', 'Conjunto')
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' (tracksuit|catsuit)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
        !~ '^ (campera|pantalon|buzo|remera|short |chaleco|jogg|calza|musculosa|bermuda|sweater|top |camiseta|chomba)';

-- 4. Funko is always a Juguete in rubro juguetes, whatever else the title says (keychain, backpack, plush, figure, a
-- swimsuit-shaped film title). The rubro is written here because renormalizar does not persist it (V90). A toy has
-- no sport, so the sub_categoria ("running" from "The Runner") and the deporte are cleared.
UPDATE productos
   SET categoria = 'Juguete', rubro = 'juguetes', sub_categoria = '', deporte = NULL
 WHERE bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '\mfunko'
   AND (categoria IS DISTINCT FROM 'Juguete'
        OR rubro IS DISTINCT FROM 'juguetes'
        OR coalesce(sub_categoria, '') <> ''
        OR deporte IS NOT NULL);

-- 5. LEGO toys get the subcategory 'lego' over any sport word ("Lego Futbol"). Whole word: "Legolas" is not a LEGO.
-- Videogames and books keep theirs (only Juguete rows are touched).
UPDATE productos
   SET sub_categoria = 'lego'
 WHERE categoria = 'Juguete'
   AND bloqueado_por IS NULL
   AND sub_categoria IS DISTINCT FROM 'lego'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '\mlego\M';
