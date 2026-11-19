-- V113__categorias_belleza.sql
--
-- New categories 'Maquillaje', 'Cuidado de la Piel' and 'Cuidado del Cabello' (rubro belleza, V112). BellezaClassifier
-- produces them and SubcategoryResolver splits them (Labios/Uñas/Ojos/Rostro, Protector Solar/Corporal/Facial,
-- Tintura/Shampoo/Acondicionador/Tratamiento); Perfume gains the sub 'Extracto'.
--
-- A new category is TWO changes: the classifier produces it, and these rows let productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without them every upsert of such a product violates the
-- FK, ProductRepository swallows the error and the symptom is "0 nuevos", not an exception.
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
-- No data step: the dev catalog holds no row these words would move; the next scrape is the source of truth.
INSERT INTO categoria (nombre) VALUES ('Maquillaje'), ('Cuidado de la Piel'), ('Cuidado del Cabello')
ON CONFLICT (nombre) DO NOTHING;
