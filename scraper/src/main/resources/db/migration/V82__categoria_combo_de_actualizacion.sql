-- V82__categoria_combo_de_actualizacion.sql
--
-- New category 'Combo de Actualización' for CPU + motherboard upgrade kits ("Kit Mother ASUS PRIME
-- A520M-K + Procesador AMD Ryzen 5 5500 + Wraith Stealth Cooler", "COMBO AMD RYZEN 7 5700X+B550+16GB").
-- They sat in Motherboard (86 active rows on dev, 2026-11-10), CPU (16) and Cooler (5) by whichever
-- leading noun won, inflating those components' medians and letting the PC builder see a kit as a part.
--
-- A new category is TWO changes: TechClassifier produces it, and this row lets productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a product violates the
-- FK, ProductRepository swallows the error and the symptom is "0 nuevos", not an exception.
-- CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
INSERT INTO categoria (nombre) VALUES ('Combo de Actualización')
ON CONFLICT (nombre) DO NOTHING;

-- Aligns the rows already stored instead of waiting for every site to be scraped again; the next scrape is
-- the source of truth. Rule (identical to TechClassifier.esComboDeActualizacion), on the accent-stripped
-- lower-cased name, boundaries = non-[a-z0-9]:
--   a bundle marker (combo, kit or a '+')  AND  a CPU signal  AND  a motherboard signal (mother, motherboard,
--   placa madre, mainboard, mobo or a chipset such as a520/b550/b650/h610/z890)  AND  no "pc gamer"-like
--   phrase and no gabinete (that is a full PC).
-- Full PCs (categoria PC / Mini PC) are never touched. Never a row locked by hand (V3). Idempotent: a handled
-- row is already 'Combo de Actualización' and no longer matches its WHERE.
UPDATE productos
   SET categoria = 'Combo de Actualización'
 WHERE bloqueado_por IS NULL
   AND rubro = 'tecnologia'
   AND categoria NOT IN ('PC', 'Mini PC', 'Combo de Actualización')
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ') ~ '( combo | kit |\+)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       ~ '(^|[^a-z0-9])(procesador|ryzen|core i[3579]|core ultra|athlon|pentium|celeron|i[3579][- ]?[0-9]{4,5}[a-z]{0,2})([^a-z0-9]|$)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       ~ '(^|[^a-z0-9])(mother|motherboard|placa madre|mainboard|mobo|([ab]3[2-7]0|a[56]20|b[3-6]50|b[3-6]60|b[78][3-6]0|h[3-8]10|z[3-9]90|x[3-8]70)[me]?)([^a-z0-9]|$)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '(pc gamer|computadora de escritorio|desktop pc|pc completa|equipo de escritorio|all in one pc| gabinete )';
