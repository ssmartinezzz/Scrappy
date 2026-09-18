-- V33__indices.sql — indices-service
--
-- Two tables for the `ar.scraper.indices` area: a lookup of the macro price
-- indices this backend tracks, and the time series of observed levels for
-- each. Both are 1FN/3FN: `indice_valor` has no repeating group and every
-- non-key column depends on the full (indice, fecha) key, not on a subset of
-- it or on another non-key column.
--
-- `indice.frecuencia` is a CHECK, not an FK to a third lookup table: two
-- literal values (`MENSUAL`, `DIARIO`) never grow into their own vocabulary
-- the way `categoria` did.

CREATE TABLE indice (
    codigo     TEXT PRIMARY KEY,
    nombre     TEXT NOT NULL,
    frecuencia TEXT NOT NULL CHECK (frecuencia IN ('MENSUAL', 'DIARIO'))
);

INSERT INTO indice (codigo, nombre, frecuencia) VALUES
    ('IPC',         'IPC INDEC nivel general', 'MENSUAL'),
    ('USD_OFICIAL', 'Dólar oficial BNA venta',  'DIARIO');

CREATE TABLE indice_valor (
    indice TEXT NOT NULL REFERENCES indice(codigo),
    fecha  DATE NOT NULL,
    valor  NUMERIC(14,4) NOT NULL CHECK (valor > 0),
    PRIMARY KEY (indice, fecha)
);
