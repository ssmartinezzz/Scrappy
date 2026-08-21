-- V26 — cuentas, roles, tokens y el dueño de los datos personales.
--
-- Primera migración de `user-accounts-and-roles`. Trae las cinco tablas de
-- identidad y le agrega un dueño a las cuatro tablas de datos personales.
--
-- ─── LO QUE ESTA MIGRACIÓN **NO** HACE ───────────────────────────────────
--
-- No cierra ninguna puerta. Al terminar esta fase no existe ningún
-- `SecurityFilterChain` y todos los endpoints siguen exactamente tan abiertos
-- como hoy. Estas tablas son cimiento: sin ellas no hay a quién autenticar,
-- pero tenerlas no protege nada por sí solo. Quien despliegue esto creyendo
-- que ya tiene control de acceso, no lo tiene.
--
-- ─── POR QUÉ EL DUEÑO ES NULLABLE, Y POR QUÉ ESO NO ES DESPROLIJIDAD ─────
--
-- Hay un orden que no se puede invertir: Flyway corre esta migración durante
-- el arranque, y la cuenta admin se siembra DESPUÉS, en un `ApplicationRunner`,
-- porque es la única etapa garantizada tras el refresh completo del contexto.
-- O sea que en el momento en que estas columnas se crean **todavía no existe
-- ninguna fila de usuario** a la que las favoritos existentes puedan
-- pertenecer.
--
-- De ahí salen dos consecuencias, y las dos son deliberadas:
--
--   1. `usuario_id` es NULLABLE. No hay id que poner.
--   2. La clave compuesta de `favoritos` viaja como UNIQUE, **no como
--      PRIMARY KEY**. Una PK prohíbe NULLs, y el dueño tiene que poder serlo.
--      La promoción a PK compuesta real es una migración posterior, cuando
--      toda fila ya tenga dueño.
--
-- La adopción de las filas huérfanas la hace el seeder en código, dentro de la
-- misma transacción en que confirma la cuenta admin. Por eso acá no hay ni una
-- credencial ni un id literal: este archivo queda congelado byte a byte apenas
-- se aplica —Flyway valida checksums— así que un secreto escrito acá no se
-- puede sacar nunca más, y este es un repositorio público.
--
-- ─── EL ÍNDICE PARCIAL NO ES DECORACIÓN ──────────────────────────────────
--
-- `favoritos.url` es PRIMARY KEY hoy, así que la misma url no se puede guardar
-- dos veces. Al soltar esa PK, `UNIQUE (usuario_id, url)` NO alcanza para
-- conservar la garantía: en SQL dos NULL son distintos entre sí, y mientras la
-- aplicación siga escribiendo `usuario_id = NULL` —que es todo esta fase— los
-- dos inserts de la misma url pasarían. Sería una regresión silenciosa
-- introducida justo por la migración que se suponía aditiva.
--
-- `uq_fav_unowned_url` conserva la garantía actual exactamente durante la
-- ventana en la que se puede violar, y deja de restringir apenas la fila tiene
-- dueño de verdad. `NULLS NOT DISTINCT` de Postgres 15 diría lo mismo en una
-- cláusula, pero la versión del Postgres portable de `_tools/pgsql` no está
-- fijada, y un índice parcial funciona en cualquier versión.
--
-- OJO: `ON CONFLICT (url)` no infiere un índice parcial solo. Todo upsert
-- contra `favoritos` tiene que repetir el `WHERE usuario_id IS NULL` del
-- índice, o Postgres rechaza la sentencia entera — el primer insert incluido.
--
-- ─── REQUISITO DE VERSIÓN ────────────────────────────────────────────────
--
-- `gen_random_uuid()` es núcleo desde Postgres 13. No se usa `pgcrypto` para
-- no depender de una extensión que puede requerir superusuario.
--
-- Rollback documentado y ejecutado por un test: ver `docs/DATABASE.md`.

-- ─── Identidad ───────────────────────────────────────────────────────────

CREATE TABLE usuario (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username            TEXT NOT NULL UNIQUE,
    email               TEXT UNIQUE,
    password_hash       TEXT NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activo              BOOLEAN NOT NULL DEFAULT TRUE,
    es_servicio         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- El login identifica por `username`; `email` es opcional y sólo lo usa el
    -- reseteo de contraseña. Guardarlo siempre en minúscula es lo que hace que
    -- el UNIQUE signifique algo: si no, "Ana@x.com" y "ana@x.com" conviven.
    CONSTRAINT chk_usuario_email_lower
        CHECK (email = lower(email)),

    -- Que una cuenta de servicio no sea reseteable es un INVARIANTE DE
    -- ESQUEMA, no una decisión de la aplicación. El flujo de reseteo busca por
    -- email; una cuenta sin email no puede ser encontrada por él. Eso es más
    -- fuerte que un `if (es_servicio) return;` que alguien puede olvidar.
    CONSTRAINT chk_usuario_servicio_sin_email
        CHECK (NOT es_servicio OR email IS NULL)
);

-- Vocabulario cerrado: la matriz de autorización conoce dos roles y nada más.
-- Un tercer rol insertado a mano no tendría ninguna regla que lo gobierne, y
-- "sin regla" en un modelo deny-by-default es un usuario que no puede hacer
-- nada — o peor, según cómo se escriba la regla.
CREATE TABLE rol (
    id     SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre TEXT NOT NULL UNIQUE,
    CONSTRAINT chk_rol_nombre_domain CHECK (nombre IN ('ADMIN', 'VIEWER'))
);

INSERT INTO rol (nombre) VALUES ('ADMIN'), ('VIEWER');

CREATE TABLE usuario_rol (
    usuario_id UUID     NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    rol_id     SMALLINT NOT NULL REFERENCES rol(id)     ON DELETE CASCADE,
    PRIMARY KEY (usuario_id, rol_id)
);

-- `token_hash`, nunca `token`: una columna llamada `token` termina, tarde o
-- temprano, con un token en claro adentro. El nombre es media defensa.
CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash TEXT        NOT NULL UNIQUE,
    family_id  UUID        NOT NULL,
    csrf_nonce TEXT,
    usuario_id UUID        NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_usuario ON refresh_token(usuario_id);
CREATE INDEX idx_refresh_token_family  ON refresh_token(family_id);

-- Va acá y no en una migración posterior por la misma razón que todo lo
-- demás: una migración aplicada queda congelada, así que agregar la tabla
-- después obligaría a otro archivo. Se pliega ahora, antes de que V26 exista
-- en ninguna instalación.
CREATE TABLE password_reset_token (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_hash  TEXT        NOT NULL UNIQUE,
    usuario_id  UUID        NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reset_token_usuario ON password_reset_token(usuario_id);

-- ─── Dueño de los datos personales ───────────────────────────────────────
--
-- Cuatro tablas, no seis. `saved_outfit_item` queda afuera porque es hija de
-- `saved_outfits` por CASCADE y hereda el dueño por el padre — una segunda
-- columna de dueño podría contradecir a la primera. Y `outfit_feedback` no
-- queda afuera: ya no existe, la borró `V15`.

ALTER TABLE favoritos
    ADD COLUMN usuario_id UUID REFERENCES usuario(id) ON DELETE CASCADE;
ALTER TABLE saved_outfits
    ADD COLUMN usuario_id UUID REFERENCES usuario(id) ON DELETE CASCADE;
ALTER TABLE outfit_feedback_item
    ADD COLUMN usuario_id UUID REFERENCES usuario(id) ON DELETE CASCADE;
ALTER TABLE categoria_dismiss
    ADD COLUMN usuario_id UUID REFERENCES usuario(id) ON DELETE CASCADE;

CREATE INDEX idx_fav_usuario         ON favoritos(usuario_id);
CREATE INDEX idx_saved_outfits_usuario ON saved_outfits(usuario_id);
CREATE INDEX idx_ofi_usuario         ON outfit_feedback_item(usuario_id);
CREATE INDEX idx_catdismiss_usuario  ON categoria_dismiss(usuario_id);

-- ─── `favoritos`: de clave natural a clave subrogada ─────────────────────
--
-- Dos personas distintas pueden marcar el mismo producto, así que `url` sola
-- ya no identifica una fila. La forma subrogada es la misma que `V1` le dio a
-- todas las demás tablas hijas.

ALTER TABLE favoritos DROP CONSTRAINT favoritos_pkey;
ALTER TABLE favoritos ADD COLUMN id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY;
ALTER TABLE favoritos ADD CONSTRAINT uq_fav_owner_url UNIQUE (usuario_id, url);

CREATE UNIQUE INDEX uq_fav_unowned_url ON favoritos(url) WHERE usuario_id IS NULL;
