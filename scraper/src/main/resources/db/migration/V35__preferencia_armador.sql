-- V35__preferencia_armador.sql — pc-builder-gama
--
-- The user's last-picked build tier (gama), budget and GPU flag, persisted
-- per account so `/pcs` can preload the form instead of starting blank.
-- `gama` follows `rol` (V26): a seeded lookup with a closed CHECK domain,
-- so a saved build's `gama_id` is a real FK and can never name a tier the
-- app does not know.
--
-- `preferencia_armador` is one row per user — a UI setting that gets
-- overwritten, not an event log. Two partial unique indexes instead of a
-- plain `UNIQUE (usuario_id)`, for the same reason `favoritos` needed two in
-- V26: SQL treats every NULL as distinct, so a plain UNIQUE would cap
-- authenticated users at one row while letting anonymous rows through
-- unbounded. Every upsert against this table has to repeat the exact `WHERE`
-- of the index it targets — Postgres does not infer a partial index.
--
-- `saved_pcs.gama_id` is nullable on purpose: a build saved before this
-- migration has no gama to report, and none can be invented after the fact.

CREATE TABLE gama (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_gama_nombre_domain
        CHECK (nombre IN ('ECONOMICA', 'MEDIA', 'ALTA'))
);
INSERT INTO gama (nombre) VALUES ('ECONOMICA'), ('MEDIA'), ('ALTA');

CREATE TABLE preferencia_armador (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id  uuid,
    gama_id     smallint NOT NULL REFERENCES gama(id),
    presupuesto double precision,
    con_gpu     boolean NOT NULL DEFAULT false,
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_preferencia_armador_usuario
        FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_preferencia_armador_usuario
    ON preferencia_armador (usuario_id) WHERE usuario_id IS NOT NULL;
CREATE UNIQUE INDEX uq_preferencia_armador_anonimo
    ON preferencia_armador ((true)) WHERE usuario_id IS NULL;

ALTER TABLE saved_pcs ADD COLUMN gama_id smallint REFERENCES gama(id);
