-- Uso: psql -v tag=<etiqueta> -v productos=<N> -v pct=<descuento, 10> -f drop.sql
-- Una sola llamada a sp_upsert_run que baja <pct>% todos los productos de la etiqueta.
SELECT sp_upsert_run((
  SELECT jsonb_agg(jsonb_build_object('url', 'https://s.test/' || :'tag' || '/' || g, 'sitio', 'SitioPerf',
         'nombre', 'Producto ' || g, 'precio', 1000 * (100 - :pct) / 100.0, 'categoria', 'Remera', 'genero', 'unisex',
         'now', '2026-10-14T11:00:00Z', 'fecha', '2026-10-15'))
  FROM generate_series(1, :productos) g), false) AS bajas;
