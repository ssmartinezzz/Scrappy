-- V31__categorias_tech_deporte.sql — richer-category-taxonomy
--
-- Quince categorías nuevas entran a la tabla lookup de V13.
--
-- POR QUÉ HACE FALTA ESTA MIGRACIÓN: `productos.categoria` tiene FK a
-- `categoria(nombre)` desde V13. Sin estas filas, TODO producto que el
-- clasificador nuevo mande a una de ellas viola la FK en el upsert — y como
-- `ProductRepository` se traga los errores SQL y devuelve UpsertStats(0,0,0,0),
-- el síntoma NO sería un error sino "0 nuevos" en un run que se ve sano.
-- `CategoriaLookupTableTest.laTablaYElCanonDeJavaNoPuedenDiverger` exige que
-- esta tabla y `CategoryGroups.canonicalCategories()` sean el MISMO conjunto,
-- exactamente para que esto no se pueda olvidar.
--
-- DE DÓNDE SALIÓ LA LISTA: de contar, no de imaginar. Medido sobre las 16.830
-- filas activas, `Otros` tenía 2.974 productos — 14% del catálogo — y adentro
-- había 453 teclados, 302 mouses, 285 fuentes, 231 discos, 161 productos de
-- red, 130 cables, 101 de impresión, 89 pelotas y 88 mousepads. No estaban mal
-- clasificados: ningún keyword los nombraba. El criterio de alta fue ≥20
-- productos reales con sustantivo propio y ninguna categoría existente donde
-- entren sin mentir.
--
-- LA EXCEPCIÓN ES `Cooler`, y es la que más importaba: no venía de `Otros`
-- sino de adentro de `CPU`, donde 321 de 646 filas eran disipadores. Media
-- categoría a un orden de magnitud de precio de la otra media no le miente a
-- un filtro — le miente a la distribución de la que vive el pipeline ML.
--
-- `Almacenamiento` NO está acá: ya entró al canon y a esta tabla en V13. Lo
-- que le faltaba era un keyword que la produjera, y eso es código, no esquema.
--
-- ROLLBACK (documentado, no ejecutable como parte de la migración — una
-- migración aplicada es byte-frozen, ver docs/DATABASE.md):
--
--   UPDATE productos SET categoria = 'Otros'
--    WHERE categoria IN ('Cooler','Fuente','Motherboard','Red','Cable',
--                        'Impresión','Mousepad','Joystick','Micrófono','UPS',
--                        'Tablet','Cámara','Reloj','Pelota','Paleta');
--   DELETE FROM categoria_stats WHERE categoria IN (...la misma lista...);
--   DELETE FROM categoria        WHERE nombre    IN (...la misma lista...);
--
-- El UPDATE va PRIMERO y los DELETE después: `productos.categoria` y
-- `categoria_stats.categoria` tienen FK a esta tabla (V13 y V16), así que
-- borrar la fila de lookup con productos todavía apuntándole falla.

INSERT INTO categoria (nombre) VALUES
    -- tecnología
    ('Cooler'), ('Fuente'), ('Motherboard'), ('Red'), ('Cable'),
    ('Impresión'), ('Mousepad'), ('Joystick'), ('Micrófono'), ('UPS'),
    ('Tablet'), ('Cámara'), ('Reloj'),
    -- equipamiento deportivo
    ('Pelota'), ('Paleta')
ON CONFLICT (nombre) DO NOTHING;
