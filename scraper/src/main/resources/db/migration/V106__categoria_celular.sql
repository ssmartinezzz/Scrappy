-- V106__categoria_celular.sql
--
-- New category 'Celular': phones and what is sold for them, split by sub_categoria (Smartphone, Funda, Protector,
-- Cargador, Cable, Soporte, Repuesto). Rubro tecnologia (CategoryGroups.CATEGORIAS_TECH).
--
-- A new category is TWO changes: CelularClassifier produces it, and this row lets productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a product violates the
-- FK, ProductRepository swallows the error and the symptom is "0 nuevos", not an exception.
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
-- No data step: the next scrape is the source of truth (the dev catalog has no phone store).
INSERT INTO categoria (nombre) VALUES ('Celular')
ON CONFLICT (nombre) DO NOTHING;
