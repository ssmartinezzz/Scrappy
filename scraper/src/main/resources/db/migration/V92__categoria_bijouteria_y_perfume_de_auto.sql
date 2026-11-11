-- V92__categoria_bijouteria_y_perfume_de_auto.sql
--
-- 1. New category 'Bijouterie' (aros, collar, choker, anillo, pulsera, broche, scrunchie, hebilla, pin), produced by
-- AccesoriosClassifier from KW_BIJOUTERIE_LIDER. A new category is TWO changes: the classifier produces it, and this
-- row lets productos.categoria (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a
-- product violates the FK, ProductRepository swallows the error and the symptom is "0 nuevos".
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Bijouterie')
ON CONFLICT (nombre) DO NOTHING;

-- 2. A car freshener ("Perfume para Auto ...") is Limpieza de Auto, next to "aromatizante" (KW_LIMPIEZA_AUTO_LIDER),
-- not a Perfume: it leaves perfumeria for hogar, where CategoryGroups files that category. Only names that LEAD with the
-- phrase (the classifier's rule), never a row locked by hand (V3). Idempotent: a handled row is no longer 'Perfume'.
UPDATE productos
   SET categoria = 'Limpieza de Auto', rubro = 'hogar'
 WHERE categoria = 'Perfume'
   AND bloqueado_por IS NULL
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
        ~ '^ (perfume para auto|perfumes para auto|perfume de auto|perfume auto )';
