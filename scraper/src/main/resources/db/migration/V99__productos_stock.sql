-- V99__productos_stock.sql
--
-- Stock state per product, written by sp_upsert_run in the same transaction as the price.
-- en_stock NULL = the platform gave no signal (never defaulted to true: an abstention is not "in
-- stock"). stock_chequeado_at is when the state was last known, so staleness is judged at read time;
-- visto_en_stock_at is the last run that saw it in stock.
ALTER TABLE productos
    ADD COLUMN en_stock           BOOLEAN     NULL,
    ADD COLUMN visto_en_stock_at  TIMESTAMPTZ NULL,
    ADD COLUMN stock_chequeado_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT ck_productos_stock_chequeado
        CHECK (en_stock IS NULL OR stock_chequeado_at IS NOT NULL);
