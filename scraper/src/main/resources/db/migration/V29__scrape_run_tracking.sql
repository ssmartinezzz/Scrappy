-- V29__scrape_run_tracking.sql — scrape-run-persistence-and-resume, slice 1
--
-- A run becomes a persisted, addressable entity. Everything else in this change
-- follows from that one fact: the run's `started_at` is the reader-isolation
-- bound, its `scrape_run_site` rows are the authoritative site set, and a row
-- left RUNNING with `finished_at IS NULL` at boot is the interruption signal.
--
-- ─── POR QUÉ `elapsed_time` NO ESTÁ ──────────────────────────────────────
--
-- Se deriva de `finished_at - started_at`. Guardarla sería una dependencia
-- funcional sobre no-clave — 3FN — y, peor que la teoría, una segunda cosa
-- que mantener sincronizada: cualquier corrección de `finished_at` dejaría la
-- duración mintiendo, sin que nada lo señale. Se calcula al leer.
--
-- ─── EL CHECK APAREADO ES EL CORAZÓN DE LA DETECCIÓN ─────────────────────
--
-- `(status = 'RUNNING') = (finished_at IS NULL)` obliga a que las dos columnas
-- digan lo mismo, en las dos direcciones. Sin él la detección al arranque —
-- "buscá un run RUNNING sin finished_at" — deja de significar algo en cuanto
-- una sola escritura las desalinea: un COMPLETED sin `finished_at` se lee como
-- interrumpido en cada reinicio, para siempre, y un RUNNING con `finished_at`
-- se esconde de la detección justo cuando hay que encontrarlo.
--
-- ─── LA FK DE SITIO VA SOBRE LA CLAVE, NO SOBRE EL NOMBRE ────────────────
--
-- El diseño de este cambio especificaba `sitio TEXT REFERENCES sitio(nombre)`
-- justificándolo con "igual que la FK de `productos.sitio` de V23". Las dos
-- mitades son falsas y este repo ya pagó por la primera. Del header de `V23`:
--
--     El design especificaba `productos.sitio -> sitio(nombre)`. Se implementó,
--     y reventó 28 tests: `Key (sitio)=(VCP) is not present in table "sitio"`.
--
-- `V18` siembra `('Vcp','vcp')`, `('Freres','freres')`: `nombre` es display,
-- `sitio_key` es identidad, y `V23` apunta a `sitio(sitio_key)`. Acá importa
-- todavía más, porque el valor sale de `buildSiteList`, o sea
-- `SiteConfig.nombre()`, que es la clave de config en minúscula
-- (`ScraperConfig:66` recorta `sitio.` y `.url`): contra `sitio(nombre)` cada
-- insert de este archivo violaría la FK en el ARRANQUE del run, antes de
-- scrapear un solo producto.
--
-- OJO, y esto NO es lo mismo que la unión del soft-delete: aquella saca sus
-- sitios de `productos.sitio`, la forma de display, porque
-- `sp_soft_delete_ausentes` compara contra esa columna. Dos tablas, dos
-- formas, las dos correctas donde están. Unificarlas rompe una de las dos.
--
-- Y a diferencia de `productos.sitio_key`, que es `GENERATED ALWAYS AS STORED`,
-- `sitio.sitio_key` (`V18:29`) es una columna común: nadie la calcula sola. El
-- writer suministra el valor con la MISMA expresión que usa
-- `R__sp_upsert_run.sql:97`, y por eso `ScrapeRunRepository` normaliza en SQL
-- en vez de en Java — una expresión, un vocabulario.
--
-- ─── POR QUÉ `SET NULL` Y NO `CASCADE` COMO TODO V26 ─────────────────────
--
-- Todas las FK a `usuario` de `V26` son `ON DELETE CASCADE`, y está bien: son
-- los datos PERSONALES del usuario (favoritos, outfits, tokens) y borrar la
-- cuenta tiene que borrarlos. Un `scrape_run` no es dato personal: es el
-- registro operativo de lo que hizo el sistema. Cascadearlo borraría el
-- historial de corridas porque se dio de baja a un admin. Se pierde la
-- procedencia, se conserva el hecho. Igual con `cron_job_id`: borrar un job no
-- puede borrar las corridas que produjo.
--
-- La regla sigue la semántica de propiedad del dato, no un estilo de la casa.
--
-- Rollback: docs/DATABASE.md, ejecutado por V29RollbackRoundTripTest.

CREATE TABLE scrape_run (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scrape_uuid     UUID        NOT NULL UNIQUE,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ NULL,
    -- Named, like fk_scrape_run_site_sitio below and for the same reason: V26's
    -- documented rollback drops `usuario`, so it has to drop this by name
    -- first. A rollback block is not stable — it is a function of whatever
    -- schema exists when it runs, and this table adds incoming edges to three
    -- tables older migrations created.
    triggered_by    UUID        NULL,
    cron_job_id     BIGINT      NULL,
    productos_count INTEGER     NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL
        CHECK (status IN ('RUNNING','COMPLETED','CANCELLED','INTERRUPTED','ERROR')),

    CONSTRAINT fk_scrape_run_usuario
        FOREIGN KEY (triggered_by) REFERENCES usuario(id)   ON DELETE SET NULL,
    CONSTRAINT fk_scrape_run_cron_job
        FOREIGN KEY (cron_job_id)  REFERENCES cron_jobs(id) ON DELETE SET NULL,

    CONSTRAINT ck_scrape_run_running_iff_unfinished
        CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

-- Boot-time detection and the run list both read the newest runs first.
CREATE INDEX idx_scrape_run_started ON scrape_run (started_at DESC);

CREATE TABLE scrape_run_site (
    scrape_run_id   BIGINT      NOT NULL REFERENCES scrape_run(id) ON DELETE CASCADE,
    -- Named, not auto-named: this is the SECOND incoming FK on `sitio`, and
    -- V18's documented rollback (`DROP TABLE sitio`) has to drop it by name
    -- before it can run. An auto-generated name would put a Postgres
    -- implementation detail into a doc block that a test executes.
    sitio_key       TEXT        NOT NULL,
    CONSTRAINT fk_scrape_run_site_sitio
        FOREIGN KEY (sitio_key) REFERENCES sitio(sitio_key),
    status          TEXT        NOT NULL
        CHECK (status IN ('PENDING','RUNNING','DONE','ERROR','SKIPPED')),
    productos_count INTEGER     NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NULL,
    finished_at     TIMESTAMPTZ NULL,
    error           TEXT        NULL,

    -- One row per site per run: "which sites are still pending" is what resume
    -- reads, and a second row for the same site makes that question ambiguous.
    PRIMARY KEY (scrape_run_id, sitio_key)
);
