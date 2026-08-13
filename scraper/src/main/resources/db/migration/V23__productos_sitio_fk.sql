-- V23__productos_sitio_fk.sql — close-1nf-and-3nf-foundation, design E7
--                               (corregido: la FK va sobre la clave, no sobre
--                                el nombre de display)
--
-- La FK que faltaba. Sin ella `productos.sitio` seguía siendo un string suelto
-- contra una tabla que ya es la fuente autoritativa de `plataforma`,
-- `es_premium` y `rubro_forzado`.
--
-- ─── POR QUÉ NO VA SOBRE `sitio(nombre)`, QUE ES LO QUE E7 DECÍA ─────────
--
-- El design especificaba `productos.sitio -> sitio(nombre)`. Se implementó, y
-- reventó 28 tests con un mensaje que lo explica solo:
--
--     Key (sitio)=(VCP) is not present in table "sitio".
--
-- `V18` sembró ese sitio como `'Vcp'`. El scraper y los fixtures escriben
-- `'VCP'` y `'vcp'`. **Los tres son el mismo sitio**: `sitioKey()` los manda a
-- `'vcp'`. Una FK sobre `nombre` enforcea igualdad de string de display, o sea
-- sensibilidad a mayúsculas — que no es lo que significa identidad de sitio en
-- ningún otro lado de este sistema. `SiteClassification.sitioKey()` existe
-- precisamente porque el display NO es la identidad, y por eso `V18` lleva las
-- dos columnas.
--
-- Así que la FK va sobre `sitio_key`, que es la identidad de verdad.
-- `productos.sitio` queda como lo que siempre fue: lo que reportó el scraper,
-- sin reescribir.
--
-- ─── LA COLUMNA ES GENERADA, NO MANTENIDA ────────────────────────────────
--
-- `productos.sitio_key` es `GENERATED ALWAYS AS ... STORED`. No hay camino de
-- escritura que actualizarla ni forma de que se desincronice de `sitio`: es la
-- misma expresión que `sitioKey()` corre en Java, evaluada por Postgres. Una
-- columna derivada mantenida a mano habría sido una copia más para desalinear,
-- que es el problema que este PR entero vino a cerrar.
--
-- `nullif(..., '')` hace que un `sitio` vacío o nulo dé `NULL` en vez de `''`,
-- y una FK ignora los NULL — así un producto sin sitio no rebota, igual que
-- hoy.
--
-- ─── LA OTRA MITAD ESTÁ EN R__sp_upsert_run.sql ──────────────────────────
--
-- Sola, esta FK sería una trampa: un scrape de un sitio que todavía no está en
-- `sitio` la violaría DENTRO de `sp_upsert_run`, y ese error lo atrapa
-- `ProductRepository`, que loguea y devuelve `UpsertStats(0,0,0,0)` — sale
-- como `"0 nuevos"` y nunca como error. Por eso viaja junto con un
-- get-or-create que crea la fila de `sitio` antes del producto que la
-- referencia. Su `ON CONFLICT DO NOTHING` sin target cubre las dos únicas de
-- la tabla, así que cuando `'VCP'` llega y `'vcp'` ya existe bajo `'Vcp'`, no
-- inserta nada — y ahora eso está BIEN, porque la FK mira la clave.
--
-- `SitioGetOrCreateTest` prueba las dos mitades y afirma `nuevos()` ANTES que
-- cualquier columna, porque `0` es la firma exacta de un rollback tragado.
--
-- Rollback: docs/ARCHITECTURE.md, ejecutado por V23RollbackRoundTripTest.

ALTER TABLE productos
    ADD COLUMN sitio_key TEXT
    GENERATED ALWAYS AS (
        nullif(lower(regexp_replace(coalesce(sitio, ''), '[^a-zA-Z0-9]', '', 'g')), '')
    ) STORED;

-- ═══════════════════════════════════════════════════════════════════════
-- Backfill: cualquier clave de sitio observada en productos que no tenga
-- fila. `V18` ya sembró desde `SELECT DISTINCT sitio FROM productos`, pero lo
-- hizo por NOMBRE, así que una grafía distinta de un sitio ya sembrado no
-- generaba fila — y aún así su clave ya existe, con lo cual el NOT EXISTS de
-- abajo la saltea correctamente. Lo que sí cubre es un sitio escrito entre
-- V18 y esta migración. Un ADD CONSTRAINT que explota en el arranque por
-- datos que la migración podía arreglar es exactamente la migración que este
-- proyecto se negó a escribir en V11.
-- ═══════════════════════════════════════════════════════════════════════

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
SELECT DISTINCT ON (p.sitio_key)
       p.sitio, p.sitio_key, 'tiendanube', false, NULL, 'historico'
FROM productos p
WHERE p.sitio_key IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sitio s WHERE s.sitio_key = p.sitio_key)
ORDER BY p.sitio_key, p.sitio
ON CONFLICT DO NOTHING;

ALTER TABLE productos
    ADD CONSTRAINT fk_productos_sitio
    FOREIGN KEY (sitio_key) REFERENCES sitio(sitio_key);
