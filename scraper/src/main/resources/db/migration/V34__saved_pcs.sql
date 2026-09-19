-- V34__saved_pcs.sql — saved-pcs-armadores
--
-- Same shape as `saved_outfits` + `saved_outfit_item` (V14/V26): a header row
-- owned by a user, and one child row per assembled slot. `saved_pc_item.url`
-- carries no FK to `productos(url)` for the same reason as
-- `saved_outfit_item`: it is a FOTO of what the pick was when saved, not a
-- live reference — a discontinued part must not break (or block) a build the
-- user already saved.
--
-- `usuario_id` is nullable on the header, mirroring `saved_outfits.usuario_id`
-- (V26): a build saved with no authenticated subject has nobody to own it yet.

CREATE TABLE saved_pcs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id     UUID REFERENCES usuario(id) ON DELETE CASCADE,
    nombre         TEXT NOT NULL,
    presupuesto    DOUBLE PRECISION NOT NULL,
    con_gpu        BOOLEAN NOT NULL,
    total_estimado DOUBLE PRECISION NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_pcs_usuario ON saved_pcs(usuario_id);

CREATE TABLE saved_pc_item (
    pc_id         BIGINT   NOT NULL REFERENCES saved_pcs(id) ON DELETE CASCADE,
    posicion      SMALLINT NOT NULL,
    slot          TEXT     NOT NULL,
    url           TEXT     NOT NULL,
    sitio         TEXT,
    nombre        TEXT,
    precio        DOUBLE PRECISION,
    img           TEXT,
    marca         TEXT,
    socket        TEXT,
    ddr           TEXT,
    form_factor   TEXT,
    watts         INT,
    capacidad_gb  INT,
    tipo_memoria  TEXT,
    PRIMARY KEY (pc_id, posicion)
);
