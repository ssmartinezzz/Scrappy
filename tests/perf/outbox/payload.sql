-- Uso: psql -v productos=5000 -v vigilados=500 -v usuarios=200 -f payload.sql
-- Deja la base lista para abba.sh: <productos> a precio 1000, <vigilados> favoritados por 2
-- usuarios cada uno (1000 filas a encolar si bajan) y perf_payload con el lote de la corrida:
-- los primeros <vigilados> bajan 10%, los siguientes <vigilados> suben 5%, el resto no cambia.
\ir guarda.sql
\set url_base 'https://a.test/'
\set sitio 'SitioA'
TRUNCATE notificacion_precio, favoritos;
CREATE TABLE IF NOT EXISTS perf_payload(j jsonb);
TRUNCATE perf_payload;
INSERT INTO usuario(username, email, password_hash)
SELECT 'u' || g, 'u' || g || '@example.test', '$argon2id$de-prueba' FROM generate_series(1, :usuarios) g
ON CONFLICT DO NOTHING;
SELECT sp_upsert_run((
  SELECT jsonb_agg(jsonb_build_object('url', :'url_base' || g, 'sitio', :'sitio', 'nombre', 'Producto ' || g,
         'precio', 1000, 'categoria', 'Remera', 'genero', 'unisex', 'now', '2026-10-14T10:00:00Z', 'fecha', '2026-10-14'))
  FROM generate_series(1, :productos) g), false) AS sembrados;
INSERT INTO favoritos(url, sitio, nombre, usuario_id)
SELECT :'url_base' || g, :'sitio', 'p', u.id FROM generate_series(1, :vigilados) g
JOIN usuario u ON u.username = 'u' || (((g - 1) % :usuarios) + 1);
INSERT INTO favoritos(url, sitio, nombre, usuario_id)
SELECT :'url_base' || g, :'sitio', 'p', u.id FROM generate_series(1, :vigilados) g
JOIN usuario u ON u.username = 'u' || (((g + 99) % :usuarios) + 1);
INSERT INTO perf_payload
SELECT jsonb_agg(jsonb_build_object('url', :'url_base' || g, 'sitio', :'sitio', 'nombre', 'Producto ' || g,
       'precio', CASE WHEN g <= :vigilados THEN 900 WHEN g <= 2 * :vigilados THEN 1050 ELSE 1000 END,
       'categoria', 'Remera', 'genero', 'unisex', 'now', '2026-10-14T11:00:00Z', 'fecha', '2026-10-15'))
FROM generate_series(1, :productos) g;
