-- Uso: psql -v filas=1000000 -v viejas=0.5 -f purga-seed.sql   (contra la base descartable de perf)
-- Siembra <filas> avisos terminales (ENVIADA/FALLIDA a partes iguales) sin pasar por sp_upsert_run,
-- para medir la purga por retención sobre una tabla grande. <viejas> es la fracción que cae
-- fuera de la ventana de 30 días (el resto es reciente y debe sobrevivir a la purga).
-- La dedup es (usuario, url, precio_nuevo): 100 usuarios x 1000 productos x los precios que
-- hagan falta, así que ninguna fila choca con otra.
\ir guarda.sql
INSERT INTO usuario(username, email, password_hash)
SELECT 'purga' || g, 'purga' || g || '@example.test', '$argon2id$de-prueba' FROM generate_series(1, 100) g
ON CONFLICT DO NOTHING;

SELECT sp_upsert_run((
  SELECT jsonb_agg(jsonb_build_object('url', 'https://purga.test/' || g, 'sitio', 'SitioPerf',
         'nombre', 'Producto ' || g, 'precio', 1000, 'categoria', 'Remera', 'genero', 'unisex',
         'now', '2026-10-14T10:00:00Z', 'fecha', '2026-10-14'))
  FROM generate_series(1, 1000) g), false) AS productos_sembrados;

WITH u AS (SELECT array_agg(id ORDER BY username) AS ids FROM usuario WHERE username LIKE 'purga%')
INSERT INTO notificacion_precio
    (usuario_id, url, precio_anterior, precio_nuevo, creada_at, estado, intentos, proximo_intento_at, enviada_at, ultimo_error)
SELECT u.ids[1 + g % 100],
       'https://purga.test/' || (1 + (g / 100) % 1000),
       2000,
       1 + g / 100000.0,
       t.cuando - interval '1 minute',
       CASE WHEN g % 2 = 0 THEN 'ENVIADA' ELSE 'FALLIDA' END,
       1,
       t.cuando,
       CASE WHEN g % 2 = 0 THEN t.cuando END,
       CASE WHEN g % 2 = 0 THEN NULL ELSE '550 no such user' END
FROM u, generate_series(1, :filas) g,
     LATERAL (SELECT CASE WHEN random() < :viejas THEN now() - interval '45 days' - (g % 1000) * interval '1 minute'
                          ELSE now() - interval '2 days' - (g % 1000) * interval '1 minute' END AS cuando) t;

ANALYZE notificacion_precio;
SELECT count(*) AS sembradas,
       count(*) FILTER (WHERE COALESCE(enviada_at, proximo_intento_at) < now() - interval '30 days') AS fuera_de_la_ventana
FROM notificacion_precio;
