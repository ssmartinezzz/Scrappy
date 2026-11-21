-- V134__zapatilla_trekking.sql
--
-- 1. New category 'Zapatilla Trekking' (indumentaria; CalzadoPorModeloClassifier: KW_TREKKING_EXPLICITO, KW_TREKKING_MODELO).
-- A new category is TWO changes: the classifier produces it, and this row lets productos.categoria (FK fk_productos_categoria, V13) accept it.
-- Without it every upsert of such a product violates the FK, ProductRepository swallows the error and the symptom is "0 nuevos".
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Zapatilla Trekking')
ON CONFLICT (nombre) DO NOTHING;

-- 2. Moves 'Zapatilla' and its four sibling shoe categories to 'Zapatilla Trekking' when CalzadoPorModeloClassifier now says so.
-- renormalizar feeds the stored categoria back as raw and the classifier only reads the name, so a keyword change alone never moves a stored
-- row: this does. Names are lower-cased, accent-folded and space-padded like TextoClasificable.normalizado; words are plain substrings, so
-- the padding IS the word boundary. The rule, as in Java: the name carries a shoe word (zapatilla, sneaker, calzado, shoe(s), tenis,
-- footwear), is not a sneaker model nor a trailing weight (Alimentos), and either has the store's own word (trekking, hiking, senderismo)
-- or a trekking model (terrex, x ultra, anylander, ...) with no running signal: neither a running model nor a running word (trail
-- included), so Pegasus Trail and Terrex Eastrail stay Zapatilla Running. Rows locked by hand (bloqueado_por) are not touched; active and
-- inactive rows alike. Idempotent: a moved row is no longer in the five source categories. SET CONSTRAINTS flushes the deferred trigger of V40.
-- The Java side also lets the rest of the classifier chain run first (CalzadoClassifier sends every "hiking" name to Borcego, so none of
-- those is here); V134ZapatillaTrekkingTest runs both over the same names.
SET CONSTRAINTS ALL IMMEDIATE;

WITH textos AS (
  SELECT p.url, btrim(p.nombre) AS crudo,
         ' ' || lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')) || ' ' AS n
    FROM productos p
   WHERE p.categoria IN ('Zapatilla', 'Zapatilla Running', 'Zapatilla Entrenamiento', 'Zapatilla Skate', 'Zapatilla Urbana')
     AND p.bloqueado_por IS NULL
),
elegidas AS (
  SELECT t.url
    FROM textos t
   WHERE EXISTS (SELECT 1 FROM unnest(string_to_array('zapatilla,sneaker,calzado, shoe , shoes ,tenis,footwear', ',')) w WHERE position(w IN t.n) > 0)
     AND NOT EXISTS (SELECT 1 FROM unnest(string_to_array('jordan 1,jordan 4,jordan 11,jordan 3,jordan 6,jordan 5,air jordan,travis scott,off-white,fragment,union la,chicago,bred,university blue,royal,yeezy,boost 350,boost 700,foam runner,dunk low,dunk high,sb dunk,panda dunk,air max 1 ,air max 90,air max 95,air max 97,new balance 550,new balance 990,new balance 2002,new balance 574,new balance 327', ',')) w WHERE position(w IN t.n) > 0)
     AND t.crudo !~* '\d+\s*(g|ml|kg|mg|oz|l)\s*$'
     AND (
       EXISTS (SELECT 1 FROM unnest(string_to_array(' trekking , hiking , senderismo ', ',')) w WHERE position(w IN t.n) > 0)
       OR (
         EXISTS (SELECT 1 FROM unnest(string_to_array(' terrex , x ultra , x-ultra , anylander , skychaser , columbia , merrell , outdoor ', ',')) w WHERE position(w IN t.n) > 0)
         AND position(' montagne city ' IN t.n) = 0
         AND NOT EXISTS (SELECT 1 FROM unnest(string_to_array('ultraboost,adizero,solarboost,duramo,pegasus,vomero,air zoom,free run,air max,gel-kayano,gel-nimbus,gel-cumulus,gel-pulse,glycerin,beast,ghost,adrenaline,levitate,triumph,endorphin,kinvara,ride,clifton,bondi,speedgoat,hoka mach, mach 4, mach 5, mach 6,fresh foam, 1080 , 1080v,990,880,860,wave rider,wave inspire,wave horizon,speedcross, sense ,running,correr,corrida,maraton,marathon,trail,atletismo,atletica,ligera,velocidad', ',')) w WHERE position(w IN t.n) > 0)
       )
     )
)
UPDATE productos AS pr
   SET categoria = 'Zapatilla Trekking'
  FROM elegidas e
 WHERE pr.url = e.url;

-- 3. Sport sub for shoes: tier 2 of SubcategoryResolver now knows 'basquet' (básquet), 'handball' and 'golf'. Only rows with no sub yet, only
-- the shoe categories (the sport tier skips beauty, technology, TCG, LEGO and Funko rows, and some categories have their own resolver:
-- replicating all of that here is not worth the risk, so other categories pick the new words up on their next renormalizar). The tier-2
-- order decides: a name that also says tenis, padel or voley keeps the earlier sport, as in Java. 'running' is skipped in 'Zapatilla Running'.
-- One single-line blob, 'sub|palabras' joined by ';' and words by ','. Idempotent: a row with a sub is not touched.
WITH reglas AS (
  SELECT split_part(f.fila, '|', 1) AS etiqueta, string_to_array(split_part(f.fila, '|', 2), ',') AS palabras, f.orden
    FROM unnest(string_to_array('natación|natacion,swimming,swim cap;hockey|hockey;fútbol|futbol,football;pádel|padel;tenis|tenis,tennis;running|running,runner;vóley|voley,volleyball;básquet|basket,basketball,basquet;handball|handball;golf|golf', ';')) WITH ORDINALITY AS f(fila, orden)
),
textos AS (
  SELECT p.url, p.categoria,
         ' ' || lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')) || ' ' AS n
    FROM productos p
   WHERE p.bloqueado_por IS NULL
     AND COALESCE(p.sub_categoria, '') = ''
     AND (p.categoria LIKE 'Zapatilla%' OR p.categoria = 'Sneaker')
),
elegidas AS (
  SELECT DISTINCT ON (t.url) t.url, r.etiqueta
    FROM textos t
    JOIN reglas r
      ON NOT (r.etiqueta = 'running' AND t.categoria LIKE '%Running%')
     AND EXISTS (SELECT 1 FROM unnest(r.palabras) w WHERE position(' ' || w || ' ' IN t.n) > 0)
   ORDER BY t.url, r.orden
)
UPDATE productos AS pr
   SET sub_categoria = e.etiqueta
  FROM elegidas e
 WHERE pr.url = e.url
   AND e.etiqueta IN ('básquet', 'handball', 'golf');
