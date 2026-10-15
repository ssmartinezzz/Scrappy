-- V49__notificacion_precio.sql — transactional outbox for price-drop emails
--
-- sp_upsert_run (R__sp_upsert_run.sql) enqueues one row per (usuario, url, new
-- price) in the same transaction that writes the price; a relay drains them.
-- The email address is NOT copied here: it is read from `usuario` at send time.
-- Full rationale and rollback: docs/DATABASE.md.

ALTER TABLE usuario
    ADD COLUMN notificar_bajas_precio boolean NOT NULL DEFAULT true;

CREATE TABLE notificacion_precio (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id         UUID             NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    url                TEXT             NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
    precio_anterior    DOUBLE PRECISION NOT NULL,
    precio_nuevo       DOUBLE PRECISION NOT NULL,
    creada_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    estado             TEXT             NOT NULL DEFAULT 'PENDIENTE',
    intentos           INTEGER          NOT NULL DEFAULT 0,
    proximo_intento_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
    enviada_at         TIMESTAMPTZ,
    ultimo_error       TEXT,
    CONSTRAINT uq_notificacion_precio UNIQUE (usuario_id, url, precio_nuevo),
    CONSTRAINT chk_notificacion_precio_estado
        CHECK (estado IN ('PENDIENTE', 'ENVIADA', 'FALLIDA')),
    CONSTRAINT chk_notificacion_precio_intentos CHECK (intentos >= 0),
    CONSTRAINT chk_notificacion_precio_enviada
        CHECK (estado <> 'ENVIADA' OR enviada_at IS NOT NULL)
);

CREATE INDEX idx_notificacion_precio_pendientes
    ON notificacion_precio (proximo_intento_at)
    WHERE estado = 'PENDIENTE';

-- favoritos is only indexed by (usuario_id, url) and by the unowned partial; the
-- price-drop lookup goes url -> watchers.
CREATE INDEX idx_fav_url ON favoritos (url);
