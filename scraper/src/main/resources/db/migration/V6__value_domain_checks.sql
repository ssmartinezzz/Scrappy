-- V6__value_domain_checks.sql — normalize-db-schema-fks-1nf, slice A.3
-- (spec "db-column-domains" > "CHECK domain admissibility", design D7)
--
-- Adds three CHECK constraints enumerating the exact value domain each
-- column is allowed to hold, per the live-data verification in obs #839:
--   genero     IN ('hombre','mujer','unisex','infantil','')  -- '' is
--                  GenderResolver's abstention sentinel — CODE-5 forbids
--                  making "no opinion" illegal
--   rubro      IN ('indumentaria','tecnologia','suplementos')
--   ml_segment IN ('budget','standard','premium','luxury')
-- NULL is admitted on all three: none of these columns is NOT NULL
-- (V1__baseline.sql:38,44,46), and while #839 found no NULLs on the dev DB,
-- a foreign install is not this dev box.
--
-- All three ship VALID (default for ADD CONSTRAINT, no NOT VALID here) —
-- #839 enumerated the domains exhaustively against the live catalog
-- (rubro/ml_segment: zero violations; genero: exactly one, fixed below), so
-- there is no unknown-orphan risk symmetrical to the favoritos FK in V4
-- (design D8).

-- Step 1: normalize the one known live anomaly (#839: a single capital-M
-- 'Mujer' row) before the CHECK is added, or the ADD CONSTRAINT below fails
-- outright.
UPDATE productos
    SET genero = lower(trim(genero))
    WHERE genero IS NOT NULL AND genero <> lower(trim(genero));

-- Step 2: defensive degrade for any install this migration runs against that
-- is not this dev DB — an out-of-domain genero, even after normalizing casing
-- and whitespace, downgrades to '' (the same abstention sentinel
-- GenderResolver itself returns) rather than aborting the migration mid-boot
-- on data an operator cannot fix without raw SQL.
UPDATE productos
    SET genero = ''
    WHERE genero IS NOT NULL
      AND genero NOT IN ('hombre', 'mujer', 'unisex', 'infantil', '');

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_genero_domain
        CHECK (genero IS NULL OR genero IN ('hombre', 'mujer', 'unisex', 'infantil', ''));

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos'));

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_ml_segment_domain
        CHECK (ml_segment IS NULL OR ml_segment IN ('budget', 'standard', 'premium', 'luxury'));
