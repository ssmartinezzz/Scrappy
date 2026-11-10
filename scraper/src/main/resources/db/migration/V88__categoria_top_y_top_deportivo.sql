-- V88__categoria_top_y_top_deportivo.sql
--
-- A "top" used to be a Musculosa (GarmentTaxonomy.KW_MUSCULOSA held " top ", "top deportivo", "sports bra" and
-- "corpino deportivo"), so 367 sport tops, fashion tops and even a sealant injector sat next to the tank tops.
-- IndumentariaClassifier now splits them:
--   - "Top Deportivo": top deportivo / sports bra / corpino deportivo, or a bare top with a sport signal or a sport brand;
--   - "Corpino": a bare top that also names lingerie (encaje, lencer, puntilla, interior, bralette);
--   - "Top": any other bare top, and croptop / crop top (AccesoriosClassifier, KW_TOP_TARDIO);
--   - "Musculosa" keeps musculosa / tank top / camiseta de tirantes / sin mangas. The leading noun wins:
--     "Top Under Armour Halo Run Tank" is a top, "Tank Top" is a Musculosa, "Top Cuello Alto" is a Remera.
-- CanchaClassifier files a name that leads with "canillera(s)" as 'Accesorio Deportivo' (it used to land in Otros,
-- Botines, Calzoncillos, Medias or Sneaker), and BicicletasClassifier files "inyector de sellante" and
-- "sellante ... tubeless" as 'Repuesto de Bicicleta'.
--
-- A new category is TWO changes: the classifier produces it, and these rows let productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without the rows every upsert of such a product violates the
-- FK, ProductRepository swallows the error and the symptom is "0 nuevos", not an exception.
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Top'), ('Top Deportivo')
ON CONFLICT (nombre) DO NOTHING;

-- Aligns the stored rows instead of waiting for every site to be scraped again; the next scrape is the source of
-- truth. Each branch of the CASE below is the SQL twin of the classifier rule named next to it, over the accent-stripped,
-- lower-cased, space-padded name (normalized once, in `n`). Never a row locked by hand (V3); the garment rules read only
-- the categories the classifier used to produce for these names and only clothing (NULL/blank rubro counts as
-- indumentaria). Idempotent: a handled row already sits in its target category and the final WHERE skips it.
WITH k AS (
    SELECT '( deportiv| training| workout| running| run | gym| fitness| yoga| soporte| impacto| dri-fit| dri fit| techfit| compression| compresion| sport | sports | sportswear| crossback| adidas | nike | puma | asics | saucony | under armour | topper | reebok | new balance | atomik | fila | mizuno | umbro | lotto | kappa | diadora )' AS deporte,
           '( encaje| lencer| puntilla| interior| bralette)' AS lenceria,
           '(corpino| bra |bralette|sosten|sujetador|bikini top|top interior|ropa interior femenina)' AS corpino,
           '(musculosa|tank top|camiseta de tirantes|sin mangas)' AS musculosa
), n AS (
    SELECT url,
           categoria AS cat,
           COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria' AS ropa,
           ' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ' AS t
      FROM productos
     WHERE bloqueado_por IS NULL
), destino AS (
    SELECT n.url,
           CASE
               -- BicicletasClassifier: the sealant injector, or a sealant named with tubeless, whatever its rubro.
               WHEN n.t ~ '^ inyector (de )?sellante ' OR (n.t ~ '^ sellante ' AND n.t ~ ' tubeless')
                   THEN 'Repuesto de Bicicleta'
               WHEN NOT n.ropa THEN NULL
               -- KW_TOP_DEPORTIVO, checked before KW_CORPINO ("sports bra" holds " bra ").
               WHEN n.cat IN ('Musculosa', 'Corpino') AND n.t ~ '(top deportivo|sports bra|corpino deportivo)'
                   THEN 'Top Deportivo'
               -- A bare top filed as Musculosa, unless a later tank noun qualifies it (the leading noun wins).
               WHEN n.cat = 'Musculosa' AND n.t ~ ' top ' AND n.t !~ 'top cuello'
                    AND (n.t ~ '^ top ' OR n.t !~ k.musculosa)
                   THEN CASE WHEN n.t ~ k.lenceria THEN 'Corpino'
                             WHEN n.t ~ k.deporte  THEN 'Top Deportivo'
                             ELSE 'Top' END
               -- croptop / crop top filed as Corpino (KW_CORPINO_TARDIO): "crop top" holds " top " and follows the sport
               -- rule; the one-word "croptop" is only ever a Top. Bra-like words keep it in Corpino.
               WHEN n.cat = 'Corpino' AND n.t ~ '( croptop | crop top )' AND n.t !~ k.corpino AND n.t !~ k.lenceria
                   THEN CASE WHEN n.t ~ ' crop top ' AND n.t ~ k.deporte THEN 'Top Deportivo' ELSE 'Top' END
               -- KW_CANILLERA_LIDER: the name leads with canillera(s), whatever else it says.
               WHEN n.cat IN ('Otros', 'Botines', 'Calzoncillos', 'Medias', 'Sneaker') AND n.t ~ '^ canilleras? '
                   THEN 'Accesorio Deportivo'
           END AS nueva
      FROM n CROSS JOIN k
)
UPDATE productos p
   SET categoria = d.nueva,
       -- The bike categories live in rubro 'deporte'.
       rubro = CASE WHEN d.nueva = 'Repuesto de Bicicleta' THEN 'deporte' ELSE p.rubro END
  FROM destino d
 WHERE p.url = d.url
   AND d.nueva IS NOT NULL
   AND p.categoria IS DISTINCT FROM d.nueva;

-- Gender follows the category (GenderResolver.FEMININE_CODED_CATEGORIES: a bare top is a women's garment)
-- unless the name carries an explicit men signal or the row is children's. Same rule as V84's Bombacha block.
UPDATE productos
   SET genero = 'mujer'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Top', 'Top Deportivo')
   AND genero IS DISTINCT FROM 'mujer'
   AND COALESCE(genero, '') <> 'infantil'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '(de hombre|para hombre| hombre|masculino|caballero)';
