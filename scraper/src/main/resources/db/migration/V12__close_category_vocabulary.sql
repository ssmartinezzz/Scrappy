-- V12__close_category_vocabulary.sql — close-category-vocabulary
--
-- `CategoryClassifier` tenía una rama que, cuando el breadcrumb de la tienda
-- no matcheaba ningún keyword, devolvía su PRIMERA PALABRA capitalizada. Con
-- eso el vocabulario de `categoria` era ABIERTO: cada tienda podía inventar
-- una categoría. Y las que inventó estaban MAL — medido sobre las 6540 filas
-- activas de la base de desarrollo:
--
--   Indumentaria    363   el fallback genérico: un RUBRO usado como categoría
--   Almacenamiento   57   categoría REAL que faltaba en el canon (HDDs/SSDs)
--   Pc               25   "MEMORIA MICROSD 64GB KINGSTON" (y difiere de 'PC')
--   Remeron          15   una remera
--   Cooling           4   "ADAPTADOR COOLERMASTER 90° 3X8 PIN PCIE"
--   Abrigos           3   "Trench - Camel"
--   Mini              3   "Mini Morral Lindor Goma Militar"
--   Porta             2   "Porta Celular URL PU Negro"
--   Neceser           2   "Neceser Tromso Goma Negro"
--   Coat              2   "Coat Duffel Negro"
--   Tarjetas          1   "Gift Cards VCP"
--   Bufandon          1
--
-- 12 valores, 478 productos (7,3%). Esta migración los lleva al canon. No es
-- una limpieza cosmética: hoy una microSD está catalogada como "Pc" y un
-- morral como "Mini", o sea que ningún filtro razonable los encuentra.
--
-- `Almacenamiento` NO se remapea: se agregó al vocabulario canónico
-- (CategoryGroups.CATEGORIAS_TECH) porque era una categoría legítima que el
-- canon no reconocía.
--
-- Los alias viven en `CategoryAliases.java` y son la MISMA tabla que usa el
-- clasificador; `CategoryAliasesMigrationSyncTest` falla si divergen.

UPDATE productos SET categoria = 'Campera' WHERE categoria IN ('Abrigos', 'Coat');
UPDATE productos SET categoria = 'Bufanda' WHERE categoria = 'Bufandon';
UPDATE productos SET categoria = 'Remera'  WHERE categoria = 'Remeron';
UPDATE productos SET categoria = 'Bolso'   WHERE categoria IN ('Mini', 'Neceser');
UPDATE productos SET categoria = 'PC'      WHERE categoria = 'Pc';
UPDATE productos SET categoria = 'Otros'   WHERE categoria IN ('Porta', 'Cooling', 'Tarjetas', 'Indumentaria');
