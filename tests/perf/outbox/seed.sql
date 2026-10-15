-- Uso: psql -v tag=<etiqueta> -v productos=<N> -v usuarios=<U> -f seed.sql
-- Crea <usuarios> usuarios con email, <productos> productos a precio 1000 (url con la
-- etiqueta, para que cada escenario tenga URLs propias: la dedup es (usuario,url,precio_nuevo))
-- y reparte los productos en bloques contiguos: cada usuario favoritea <productos>/<usuarios> seguidos (5 con 1000/200),
-- así que una ráfaga debe dar un mail por usuario.
INSERT INTO usuario(username, email, password_hash)
SELECT 'u' || g, 'u' || g || '@example.test', '$argon2id$de-prueba' FROM generate_series(1, :usuarios) g
ON CONFLICT DO NOTHING;

SELECT sp_upsert_run((
  SELECT jsonb_agg(jsonb_build_object('url', 'https://s.test/' || :'tag' || '/' || g, 'sitio', 'SitioPerf',
         'nombre', 'Producto ' || g, 'precio', 1000, 'categoria', 'Remera', 'genero', 'unisex',
         'now', '2026-10-14T10:00:00Z', 'fecha', '2026-10-14'))
  FROM generate_series(1, :productos) g), false) AS sembrados;

INSERT INTO favoritos(url, sitio, nombre, usuario_id)
SELECT 'https://s.test/' || :'tag' || '/' || g, 'SitioPerf', 'p', u.id
FROM generate_series(1, :productos) g
JOIN usuario u ON u.username = 'u' || (((g - 1) / (:productos / :usuarios)) + 1);
