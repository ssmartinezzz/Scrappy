-- V38__categoria_mini_pc.sql — pc-builder-homelab, T1
--
-- Un solo INSERT a la tabla lookup de V13, misma forma que V31/V32: no toca
-- ningún CHECK ni ningún dominio cerrado.
--
-- POR QUÉ HACE FALTA LA MIGRACIÓN: `productos.categoria` tiene FK a
-- `categoria(nombre)` desde V13. Sin esta fila, todo mini PC que el
-- clasificador mande a "Mini PC" viola la FK en el upsert — y como
-- `ProductRepository` se traga los errores SQL y devuelve UpsertStats(0,0,0,0),
-- el síntoma no sería un error sino "0 nuevos" en una corrida que se ve sana.
-- `CategoriaLookupTableTest.laTablaYElCanonDeJavaNoPuedenDiverger` exige que
-- esta tabla y `CategoryGroups.canonicalCategories()` sean el MISMO conjunto.
--
-- DE DÓNDE SALIÓ: de contar, no de imaginar. Medido sobre el catálogo vivo
-- (dev DB, filas activas de tecnologia, 2026-09-24): 24 mini PCs, y 18 de
-- ellos vivían en `CPU` — `"Mini Pc Cx Amd Ryzen 7 6800H..."` tiene " amd " y
-- caía en KW_CPU, la misma clase de bug que las PCs armadas de la fase 7.
--
-- `Mini PC` es DISTINTA de `PC` (D1, odd/tasks/pc-builder-homelab.md): las dos
-- son un equipo completo, pero el armador homelab necesita elegirla como una
-- pieza única (D6 — modo mini PC), y un mini PC no compite por los mismos
-- slots que una torre armada con componentes sueltos.

INSERT INTO categoria (nombre) VALUES ('Mini PC')
ON CONFLICT (nombre) DO NOTHING;
