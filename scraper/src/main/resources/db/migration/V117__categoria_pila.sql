-- V117__categoria_pila.sql
--
-- New category 'Pila': AA/AAA/button cells and rechargeable ones, sold as such. Rubro hogar (CategoryGroups.CATEGORIAS_HOGAR),
-- like the other household consumables. 'Pila' as a kitchen sink is vetoed (KW_PILA_VETO). Measured 2026-11-20 on the active dev
-- catalogue: 57 rows in Otros across 3 sites start with "Pila"/"Pilas".
--
-- A new category is TWO changes: HogarClassifier produces it, and this row lets productos.categoria (FK fk_productos_categoria,
-- V13) accept it. Without the row every upsert of such a product violates the FK, ProductRepository swallows the error and
-- the symptom is "0 nuevos". CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
-- No data step: the next scrape is the source of truth.
INSERT INTO categoria (nombre) VALUES ('Pila')
ON CONFLICT (nombre) DO NOTHING;
