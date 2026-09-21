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
--
-- T5 extends this same migration (still unapplied, still editable) with
-- `producto_tech_specs` and its six lookups (`socket`, `ddr`, `form_factor`,
-- `tipo_memoria`, `certificacion`, `tipo_almacenamiento`): the normalized
-- home for what `TechSpecsParser` reads off a tech product's name, the base
-- for a future `/catalogo` specs filter (out of scope here). Every `*_id` on
-- `producto_tech_specs` is NULLABLE, and NULL means the parser abstained —
-- no lookup here seeds a DESCONOCIDA/NINGUNA row, because an abstention
-- sentinel is a Java-domain concept, not a value a foreign key can point to
-- (the same mistake `marca=''` was before V21).

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

-- Six more lookups, same molde as `gama` above: smallint identity PK +
-- `nombre` UNIQUE + a closed CHECK domain, so a spec's `*_id` can never name
-- a value TechSpecsParser doesn't produce.

CREATE TABLE socket (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_socket_nombre_domain
        CHECK (nombre IN ('AM4', 'AM5', 'LGA1700', 'LGA1851'))
);
INSERT INTO socket (nombre) VALUES ('AM4'), ('AM5'), ('LGA1700'), ('LGA1851');

CREATE TABLE ddr (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_ddr_nombre_domain
        CHECK (nombre IN ('DDR3', 'DDR4', 'DDR5'))
);
INSERT INTO ddr (nombre) VALUES ('DDR3'), ('DDR4'), ('DDR5');

CREATE TABLE form_factor (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_form_factor_nombre_domain
        CHECK (nombre IN ('ITX', 'MATX', 'ATX', 'EATX'))
);
INSERT INTO form_factor (nombre) VALUES ('ITX'), ('MATX'), ('ATX'), ('EATX');

CREATE TABLE tipo_memoria (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_tipo_memoria_nombre_domain
        CHECK (nombre IN ('DIMM', 'SODIMM'))
);
INSERT INTO tipo_memoria (nombre) VALUES ('DIMM'), ('SODIMM');

-- Certificacion.NINGUNA is not seeded: a fuente whose name states no
-- certification carries zero information, and zero information is NULL here
-- (D10), not a row.
CREATE TABLE certificacion (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_certificacion_nombre_domain
        CHECK (nombre IN ('WHITE', 'BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'TITANIUM'))
);
INSERT INTO certificacion (nombre)
    VALUES ('WHITE'), ('BRONZE'), ('SILVER'), ('GOLD'), ('PLATINUM'), ('TITANIUM');

CREATE TABLE tipo_almacenamiento (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_tipo_almacenamiento_nombre_domain
        CHECK (nombre IN ('NVME', 'SSD', 'HDD'))
);
INSERT INTO tipo_almacenamiento (nombre) VALUES ('NVME'), ('SSD'), ('HDD');

-- One row per tech product, hanging off `productos.url` like `producto_badge`
-- and `producto_talle` (ON DELETE CASCADE — a discontinued product's specs
-- are not worth keeping around as an orphan). Own write path: TechSpecsPort,
-- filled after aggregation, never through sp_upsert_run (D11) — the same
-- shape as ml_output.
CREATE TABLE producto_tech_specs (
    url                    text PRIMARY KEY REFERENCES productos(url) ON DELETE CASCADE,
    socket_id              smallint REFERENCES socket(id),
    ddr_id                 smallint REFERENCES ddr(id),
    form_factor_id         smallint REFERENCES form_factor(id),
    tipo_memoria_id        smallint REFERENCES tipo_memoria(id),
    certificacion_id       smallint REFERENCES certificacion(id),
    gama_id                smallint REFERENCES gama(id),
    tipo_almacenamiento_id smallint REFERENCES tipo_almacenamiento(id),
    watts                  integer,
    capacidad_gb           integer,
    velocidad_mhz          integer,
    actualizado_at         timestamptz NOT NULL DEFAULT now(),

    -- watts/capacidad_gb/velocidad_mhz are NULL when TechSpecsParser
    -- abstained (its own centinela is 0, not a value this table repeats) —
    -- this CHECK is what keeps that translation from ever regressing to 0.
    CONSTRAINT chk_producto_tech_specs_enteros_positivos CHECK (
        (watts IS NULL OR watts > 0)
        AND (capacidad_gb IS NULL OR capacidad_gb > 0)
        AND (velocidad_mhz IS NULL OR velocidad_mhz > 0)
    )
);
