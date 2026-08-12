-- V9__cron_job_sitios_child_table.sql — normalize-db-schema-fks-1nf, cierre de 1FN
--
-- `cron_jobs.sitios_json` era la última violación de 1FN sobre una columna que
-- el backend REALMENTE interpreta: la parsea a List<String> y con eso decide
-- qué sitios scrapea el job. Un grupo repetitivo disfrazado de TEXT.
--
-- Misma forma que producto_talle/producto_badge (V7): PK (padre, posicion),
-- FK con ON DELETE CASCADE, una columna de valor. `posicion` mantiene el orden
-- en que el usuario eligió los sitios y hace que el rollback sea lossless.
--
-- Qué NO se toca acá, y por qué:
--   * saved_outfits.slots_json / suplementos_json: el backend los serializa y
--     los devuelve verbatim, nunca consulta adentro. Son documentos del
--     cliente, no grupos repetitivos: normalizarlos sería inventarle un
--     esquema a algo que el backend deliberadamente no interpreta. Se retipan
--     a jsonb en V10 para que al menos la base valide que son JSON.
--   * outfit_feedback (legacy): no se normaliza porque no se usa — la única
--     referencia en el código es un DELETE. Borrar la tabla destruye historial
--     del usuario, así que esa decisión no la toma una migración sola.

CREATE TABLE cron_job_sitio (
    job_id   BIGINT   NOT NULL REFERENCES cron_jobs(id) ON DELETE CASCADE,
    posicion SMALLINT NOT NULL,
    sitio    TEXT     NOT NULL,
    PRIMARY KEY (job_id, posicion)
);

-- >>> backfill:cron_job_sitio
INSERT INTO cron_job_sitio (job_id, posicion, sitio)
SELECT j.id, s.ord::smallint, s.val
FROM cron_jobs j,
     LATERAL jsonb_array_elements_text(
       CASE WHEN j.sitios_json ~ '^\s*\[' THEN j.sitios_json::jsonb ELSE '[]'::jsonb END
     ) WITH ORDINALITY AS s(val, ord)
WHERE s.val <> '';
-- <<< backfill:cron_job_sitio

ALTER TABLE cron_jobs DROP COLUMN sitios_json;
