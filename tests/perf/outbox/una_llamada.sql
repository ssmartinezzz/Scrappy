-- Una medición: la llamada corre dentro de una transacción que se revierte, así la
-- base queda idéntica y cada llamada hace el mismo trabajo. :sin_vigilantes=1 borra los
-- favoritos DENTRO de esa transacción (mide el costo del chequeo sin encolar nada).
BEGIN;
DELETE FROM favoritos WHERE :sin_vigilantes = 1;
SELECT clock_timestamp() AS t0 \gset
SELECT (sp_upsert_run((SELECT j FROM perf_payload), false))::text AS r \gset
SELECT round(extract(epoch FROM clock_timestamp() - :'t0'::timestamptz) * 1000, 1) AS ms,
       (SELECT count(*) FROM notificacion_precio) AS encoladas \gset
\echo RESULTADO :ms :encoladas
ROLLBACK;
