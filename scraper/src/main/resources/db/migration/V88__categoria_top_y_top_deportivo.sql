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
-- truth. Every rule below is the SQL twin of the classifier rule named next to it, over the accent-stripped,
-- lower-cased, space-padded name. Only the categories the classifier used to produce for these names, only clothing
-- (NULL/blank rubro counts as indumentaria), never a row locked by hand (V3). Idempotent: a handled row has left
-- the category its WHERE reads from.

-- 1. KW_TOP_DEPORTIVO, checked before KW_CORPINO ("sports bra" holds " bra ").
UPDATE productos
   SET categoria = 'Top Deportivo'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Musculosa', 'Corpino')
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '(top deportivo|sports bra|corpino deportivo)';

-- 2. A bare top filed as Musculosa: lingerie-style tops go to Corpino, sport ones to Top Deportivo, the rest to Top.
UPDATE productos
   SET categoria = 'Corpino'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Musculosa'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' top '
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ 'top cuello'
   AND ((' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ top ' OR (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '(musculosa|tank top|camiseta de tirantes|sin mangas)')
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '( encaje| lencer| puntilla| interior| bralette)';

UPDATE productos
   SET categoria = 'Top Deportivo'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Musculosa'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' top '
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ 'top cuello'
   AND ((' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ top ' OR (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '(musculosa|tank top|camiseta de tirantes|sin mangas)')
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '( deportiv| training| workout| running| run | gym| fitness| yoga| soporte| impacto| dri-fit| dri fit| techfit| compression| compresion| sport | sports | sportswear| crossback| adidas | nike | puma | asics | saucony | under armour | topper | reebok | new balance | atomik | fila | mizuno | umbro | lotto | kappa | diadora )';

UPDATE productos
   SET categoria = 'Top'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Musculosa'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' top '
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ 'top cuello'
   AND ((' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ top ' OR (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '(musculosa|tank top|camiseta de tirantes|sin mangas)');

-- 3. croptop / crop top filed as Corpino (it used to be in KW_CORPINO_TARDIO): "crop top" also holds " top ", so it follows the
--    sport rule; the one-word "croptop" is only ever a Top. Bra-like words keep it in Corpino.
UPDATE productos
   SET categoria = 'Top Deportivo'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Corpino'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' crop top '
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '(corpino| bra |bralette|sosten|sujetador|bikini top|top interior|ropa interior femenina)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '( encaje| lencer| puntilla| interior| bralette)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '( deportiv| training| workout| running| run | gym| fitness| yoga| soporte| impacto| dri-fit| dri fit| techfit| compression| compresion| sport | sports | sportswear| crossback| adidas | nike | puma | asics | saucony | under armour | topper | reebok | new balance | atomik | fila | mizuno | umbro | lotto | kappa | diadora )';

UPDATE productos
   SET categoria = 'Top'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Corpino'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '( croptop | crop top )'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '(corpino| bra |bralette|sosten|sujetador|bikini top|top interior|ropa interior femenina)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ '( encaje| lencer| puntilla| interior| bralette)';

-- 4. KW_CANILLERA_LIDER: the name leads with canillera(s), whatever else it says (medias, a boot model, a boxer cut).
UPDATE productos
   SET categoria = 'Accesorio Deportivo'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Otros', 'Botines', 'Calzoncillos', 'Medias', 'Sneaker')
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ canilleras? ';

-- 5. Tubeless sealant: the injector, or a sealant named with tubeless. The bike categories live in rubro 'deporte'.
UPDATE productos
   SET categoria = 'Repuesto de Bicicleta',
       rubro = 'deporte'
 WHERE bloqueado_por IS NULL
   AND categoria <> 'Repuesto de Bicicleta'
   AND (   (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ inyector (de )?sellante '
        OR ((' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '^ sellante ' AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' tubeless'));

-- 6. Gender follows the category (GenderResolver.FEMININE_CODED_CATEGORIES: a bare top is a women's garment)
-- unless the name carries an explicit men signal or the row is children's. Same rule as V84's Bombacha block.
UPDATE productos
   SET genero = 'mujer'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Top', 'Top Deportivo')
   AND genero IS DISTINCT FROM 'mujer'
   AND COALESCE(genero, '') <> 'infantil'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '(de hombre|para hombre| hombre|masculino|caballero)';
