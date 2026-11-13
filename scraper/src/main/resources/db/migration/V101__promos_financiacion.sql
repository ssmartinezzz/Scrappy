-- V101__promos_financiacion.sql
--
-- Payment promos a store publishes: interest-free installments, discounts and surcharges per
-- bank / wallet / card / gateway / generic medio, site-wide or per product. Written by
-- sp_upsert_promos (R__sp_upsert_promos.sql) inside the run's transaction.
-- Full rationale and rollback: docs/DATABASE.md.

CREATE TABLE medio_pago (
    id     SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre TEXT NOT NULL UNIQUE,
    tipo   TEXT NOT NULL
        CONSTRAINT ck_medio_pago_tipo CHECK (tipo IN ('banco','billetera','tarjeta','pasarela','medio'))
);

INSERT INTO medio_pago (nombre, tipo) VALUES
    ('Santander','banco'), ('Galicia','banco'), ('BBVA','banco'), ('Macro','banco'), ('Nación','banco'),
    ('Provincia','banco'), ('Ciudad','banco'), ('ICBC','banco'), ('HSBC','banco'), ('Patagonia','banco'),
    ('Supervielle','banco'), ('Credicoop','banco'), ('Hipotecario','banco'), ('Comafi','banco'),
    ('Brubank','banco'), ('Naranja X','banco'),
    ('Mercado Pago','billetera'), ('Modo','billetera'), ('Ualá','billetera'), ('Cuenta DNI','billetera'),
    ('Personal Pay','billetera'),
    ('Visa','tarjeta'), ('Mastercard','tarjeta'), ('American Express','tarjeta'), ('Cabal','tarjeta'),
    ('Naranja','tarjeta'), ('Maestro','tarjeta'),
    ('Pago Nube','pasarela'), ('GOcuotas','pasarela'), ('Mercado Crédito','pasarela'),
    ('Transferencia','medio'), ('Efectivo','medio'), ('Tarjeta de crédito','medio'),
    ('Tarjeta de débito','medio');

CREATE TABLE promo_financiacion (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sitio_key       TEXT         NOT NULL,
    medio_pago_id   SMALLINT     NOT NULL REFERENCES medio_pago(id),
    tipo            TEXT         NOT NULL,
    cuotas          SMALLINT     NULL,
    pct             NUMERIC(5,2) NULL,
    alcance         TEXT         NOT NULL,
    vigencia_desde  DATE         NULL,
    vigencia_hasta  DATE         NULL,
    texto_fuente    TEXT         NULL,
    activo          BOOLEAN      NOT NULL DEFAULT true,
    primer_visto_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    visto_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Named: V18's documented rollback drops it by name (see the V63 note in docs/DATABASE.md).
    CONSTRAINT fk_promo_financiacion_sitio FOREIGN KEY (sitio_key) REFERENCES sitio(sitio_key),
    CONSTRAINT ck_promo_tipo    CHECK (tipo IN ('cuotas_sin_interes','descuento','recargo')),
    CONSTRAINT ck_promo_alcance CHECK (alcance IN ('sitio','producto')),
    CONSTRAINT ck_promo_cuotas  CHECK (cuotas IS NULL OR cuotas > 0),
    CONSTRAINT ck_promo_pct     CHECK (pct IS NULL OR (pct > 0 AND pct <= 100)),
    CONSTRAINT ck_promo_cuotas_requeridas CHECK (tipo <> 'cuotas_sin_interes' OR cuotas IS NOT NULL),
    CONSTRAINT ck_promo_pct_requerido     CHECK (tipo = 'cuotas_sin_interes' OR pct IS NOT NULL)
);

-- Expression index instead of NOT NULL sentinel defaults: cuotas/pct stay honestly NULL when the
-- promo has none, and COALESCE(...,0) is only the uniqueness key (0 is not a valid value of either).
CREATE UNIQUE INDEX uq_promo_financiacion_natural
    ON promo_financiacion (sitio_key, medio_pago_id, tipo, alcance, COALESCE(cuotas, 0), COALESCE(pct, 0));
CREATE INDEX idx_promo_financiacion_sitio_activo ON promo_financiacion (sitio_key) WHERE activo;

CREATE TABLE promo_financiacion_producto (
    promo_id BIGINT      NOT NULL REFERENCES promo_financiacion(id) ON DELETE CASCADE,
    url      TEXT        NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
    visto_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (promo_id, url)
);
CREATE INDEX idx_promo_financiacion_producto_url ON promo_financiacion_producto (url);
