-- V84__categoria_bombacha.sql
--
-- New category 'Bombacha' for women's bottom underwear (bombacha, panty, culotte, colaless, vedetina, tanga).
-- "tanga" lived in GarmentTaxonomy.KW_CALZONCILLO, so a women's tanga was filed as men's underwear.
-- IndumentariaClassifier now checks KW_BOMBACHA (minus KW_BOMBACHA_VETO) before Calzoncillos, and
-- GenderResolver treats Bombacha as feminine-coded, like Corpino.
--
-- A new category is TWO changes: the classifier produces it, and this row lets productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a product violates the
-- FK, ProductRepository swallows the error and the symptom is "0 nuevos", not an exception.
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Bombacha')
ON CONFLICT (nombre) DO NOTHING;

-- Aligns the rows already stored instead of waiting for every site to be scraped again; the next scrape is
-- the source of truth. Rule (identical to KW_BOMBACHA / KW_BOMBACHA_VETO): accent-stripped lower-cased name
-- containing bombacha, panty, panties, culotte, colaless, cola less, vedetina or tanga, and none of the swimwear,
-- trousers or gaucho-workwear words. Only the categories the classifier used to produce for these
-- (Calzoncillos through "tanga", Otros when nothing matched) and only clothing (NULL/blank rubro counts as
-- indumentaria). Never a row locked by hand (V3).
-- Idempotent: a handled row is already 'Bombacha' and no longer matches its WHERE.
UPDATE productos
   SET categoria = 'Bombacha'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Calzoncillos', 'Otros')
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       ~ '(bombacha| panty | panties |culotte|colaless|cola less|vedetina|tanga)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '(bikini|bano|malla|playa|swim|pantalon|jean|bermuda| short |campo|gaucho|gabardina|rural)';

-- Gender follows the category (GenderResolver.FEMININE_CODED_CATEGORIES) unless the name carries an explicit
-- men signal or the row is children's.
UPDATE productos
   SET genero = 'mujer'
 WHERE bloqueado_por IS NULL
   AND categoria = 'Bombacha'
   AND genero IS DISTINCT FROM 'mujer'
   AND COALESCE(genero, '') <> 'infantil'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '(de hombre|para hombre| hombre|masculino|caballero)';
