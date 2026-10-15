-- Latencia de cada fila: enviada_at - creada_at. creada_at es now() de la transacción
-- del upsert, o sea el instante en que la baja quedó encolada.
SELECT estado, count(*) AS filas,
  round(extract(epoch FROM percentile_cont(0.5)  WITHIN GROUP (ORDER BY enviada_at - creada_at))::numeric, 2) AS p50_s,
  round(extract(epoch FROM percentile_cont(0.95) WITHIN GROUP (ORDER BY enviada_at - creada_at))::numeric, 2) AS p95_s,
  round(extract(epoch FROM percentile_cont(0.99) WITHIN GROUP (ORDER BY enviada_at - creada_at))::numeric, 2) AS p99_s,
  round(extract(epoch FROM max(enviada_at - creada_at))::numeric, 2) AS max_s,
  round(extract(epoch FROM (max(enviada_at) - min(creada_at)))::numeric, 2) AS pared_s
FROM notificacion_precio GROUP BY estado ORDER BY estado;
