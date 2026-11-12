-- V96__categorias_camisaco_lego_tcg_wall_panel.sql
--
-- 1. New categories: 'Camisaco' (indumentaria), 'LEGO' and 'TCG' (juguetes), 'Wall Panel' (construccion).
-- A new category is TWO changes: the classifier produces it (KW_CAMISACO, KW_LEGO, KW_TCG*, KW_WALL_PANEL), and these rows
-- let productos.categoria (FK fk_productos_categoria, V13) accept it. Without them every upsert of such a product violates
-- the FK, ProductRepository swallows the error and the symptom is "0 nuevos".
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Camisaco'), ('LEGO'), ('TCG'), ('Wall Panel')
ON CONFLICT (nombre) DO NOTHING;

-- renormalizar feeds the stored categoria back as raw, so a keyword fix alone never moves a stored row:
-- the UPDATEs below do it. Names are space-padded and accent-folded like TextoClasificable.normalizado.
-- Never a row locked by hand (V3). Each UPDATE is idempotent: a moved row no longer matches its WHERE.
-- The rubro is written here because renormalizar does not persist it (V90).

-- 2. Camisaco was Saco ("saco " ends "camisaco "). It beats Saco and every garment noun after it.
UPDATE productos
   SET categoria = 'Camisaco'
 WHERE categoria IN ('Saco', 'Camisa')
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ 'camisaco';

-- 3. "blazer" is a Saco only as the leading noun. Anywhere else it is a shoe model (Nike Blazer), a pack (Trailblazer)
-- or a belt/sweatshirt that happens to carry the word, so each goes back to its own family.
UPDATE productos
   SET categoria = CASE
         WHEN n ~ ' (mochila|backpack)|trailblazer [0-9]' THEN 'Mochila'
         WHEN n ~ ' belt '                                 THEN 'Cinturón'
         WHEN n ~ '^ buzo'                                 THEN 'Buzo'
         WHEN n ~ ' sb .*blazer (mid|low)| blazer (mid|low).* sb ' THEN 'Zapatilla Skate'
         WHEN n ~ ' blazer (mid|low)'                      THEN 'Zapatilla Urbana'
         ELSE 'Otros'
       END
  FROM (SELECT url, (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') AS n FROM productos) q
 WHERE productos.url = q.url
   AND productos.categoria = 'Saco'
   AND productos.bloqueado_por IS NULL
   AND q.n ~ 'blazer'
   AND q.n !~ '^ blazers? ';

-- 4. A bare "top" next to a cleaning or supplement word is not a top: the BCAA goes to its supplement family,
-- the brush has no family and falls to Otros.
UPDATE productos
   SET categoria = CASE WHEN n ~ ' (bcaa|capsula|proteina|whey|creatina|suplemento)' THEN 'BCAA' ELSE 'Otros' END,
       rubro = CASE WHEN n ~ ' (bcaa|capsula|proteina|whey|creatina|suplemento)' THEN 'suplementos' ELSE rubro END
  FROM (SELECT url, (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') AS n FROM productos) q
 WHERE productos.url = q.url
   AND productos.categoria = 'Top'
   AND productos.bloqueado_por IS NULL
   AND q.n ~ ' (limpia|limpiador|cepillo|bcaa|capsula|proteina|whey|creatina|suplemento)';

-- 5. A shirt or a polo is never gymrat (GymratTagger), even from a gym brand or site.
UPDATE productos
   SET gymrat = false
 WHERE categoria IN ('Camisa', 'Chomba')
   AND gymrat;

-- 6. LEGO sets leave Juguete (and their 'lego' sub_categoria, which the new category replaces). Whole word: "Legolas" is
-- not a LEGO. Funko stays a Juguete (V94), videogames and books keep theirs (only Juguete rows are touched).
UPDATE productos
   SET categoria = 'LEGO', rubro = 'juguetes', sub_categoria = ''
 WHERE categoria = 'Juguete'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' lego[ :]'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') !~ ' funko';

-- 7. Trading card games (Juego de Mesa, or a set that read as Conjunto), with the franchise as sub_categoria.
-- Franchise order = SubcategoryResolver's tier 1 (first match wins); others stay empty. A mug, plush, figure, keychain or
-- console that names a franchise is not a card (same veto as KW_TCG_VETO), and neither is a party card game.
UPDATE productos
   SET categoria = 'TCG', rubro = 'juguetes',
       sub_categoria = CASE
         WHEN n ~ ' pokemon '                                      THEN 'pokemon'
         WHEN n ~ ' dragon ball '                                  THEN 'dragon ball'
         WHEN n ~ ' (magic the gathering|magic:|mtg) '             THEN 'magic'
         WHEN n ~ ' (yu-gi-oh|yu-gi-oh!|yugioh|yu gi oh) '         THEN 'yu-gi-oh'
         WHEN n ~ ' one piece '                                    THEN 'one piece'
         WHEN n ~ ' lorcana '                                      THEN 'lorcana'
         WHEN n ~ ' digimon '                                      THEN 'digimon'
         ELSE ''
       END
  FROM (SELECT url, (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') AS n FROM productos) q
 WHERE productos.url = q.url
   AND productos.categoria IN ('Juego de Mesa', 'Conjunto')
   AND productos.bloqueado_por IS NULL
   AND q.n !~ ' (taza | mug | llavero| keychain| funda | remera| camiseta| mochila| peluche| plush| figura| figure| nintendo switch| playstation| xbox| ps4 | ps5 )'
   AND q.n !~ ' funko'
   AND (q.n ~ '( tcg[ :,)-]| trading card| booster (pack|box|bundle|display)| elite trainer box| etb\))'
        OR (q.n ~ ' (pokemon| dragon ball| magic the gathering| magic:| mtg | lorcana| yu-gi-oh| yugioh| yu gi oh| one piece| digimon)'
            AND q.n ~ '( card game | card game:| starter deck| structure deck| booster| carddass)'));

-- 8. Wall panels leave Placa Decorativa / Vinilo Decorativo. "Panel Ranurado" (slatwall hooks and brackets) is not one.
UPDATE productos
   SET categoria = 'Wall Panel', rubro = 'construccion'
 WHERE categoria IN ('Placa Decorativa', 'Vinilo Decorativo')
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ ' (wall panel|wallpanel|panel wpc|paneles wpc)';
