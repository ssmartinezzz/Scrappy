-- V40__catalog_version.sql — catalog-facets-perf, T2. Full rationale in
-- docs/DATABASE.md; this header is just the pointer.
--
-- Single-row version stamp, bumped by trigger on every write to the tables
-- facetas()/resumen() read. Row-level triggers are DEFERRABLE INITIALLY
-- DEFERRED so the lock on catalog_version is taken once, at COMMIT, not for
-- the transaction's duration; a tx-local GUC makes a multi-row batch bump
-- exactly once regardless of row count. TRUNCATE triggers can't be deferred
-- in Postgres, so those fire immediately, same trigger function.

CREATE SEQUENCE catalog_version_seq;

CREATE TABLE catalog_version (
    id      boolean NOT NULL PRIMARY KEY DEFAULT true CHECK (id),
    version bigint  NOT NULL
);
INSERT INTO catalog_version (version) VALUES (nextval('catalog_version_seq'));

CREATE FUNCTION bump_catalog_version() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_txid text := txid_current()::text;
BEGIN
    IF current_setting('catalog_version.bumped_txid', true) IS DISTINCT FROM v_txid THEN
        UPDATE catalog_version SET version = nextval('catalog_version_seq') WHERE id;
        PERFORM set_config('catalog_version.bumped_txid', v_txid, true);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_catalog_version_productos
    AFTER INSERT OR UPDATE OR DELETE ON productos
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION bump_catalog_version();

CREATE CONSTRAINT TRIGGER trg_catalog_version_producto_talle
    AFTER INSERT OR UPDATE OR DELETE ON producto_talle
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION bump_catalog_version();

CREATE CONSTRAINT TRIGGER trg_catalog_version_producto_badge
    AFTER INSERT OR UPDATE OR DELETE ON producto_badge
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION bump_catalog_version();

-- TRUNCATE has no per-row event and Postgres does not allow a deferrable
-- TRUNCATE trigger, so these three fire immediately, once per truncated
-- table, at the TRUNCATE statement itself.
CREATE TRIGGER trg_catalog_version_productos_truncate
    AFTER TRUNCATE ON productos
    FOR EACH STATEMENT EXECUTE FUNCTION bump_catalog_version();

CREATE TRIGGER trg_catalog_version_producto_talle_truncate
    AFTER TRUNCATE ON producto_talle
    FOR EACH STATEMENT EXECUTE FUNCTION bump_catalog_version();

CREATE TRIGGER trg_catalog_version_producto_badge_truncate
    AFTER TRUNCATE ON producto_badge
    FOR EACH STATEMENT EXECUTE FUNCTION bump_catalog_version();
