-- V102__promo_red_tarjeta.sql
--
-- A promo may require a card network on top of its bank / wallet: "12 cuotas sin interes con Macro
-- (Visa, Mastercard y American Express)" is valid for a Macro Visa, a Macro Mastercard and a Macro
-- Amex, and for no other card. red_medio_pago_id is that network (a medio_pago of tipo 'tarjeta'),
-- NULL when the promo holds for any card of the medio. It is part of the natural key, so one bank with
-- three networks is three rows. That red is a tarjeta cannot be a CHECK (it reads another table):
-- sp_upsert_promos, the only writer, enforces it (R__sp_upsert_promos.sql).
-- Full rationale and rollback: docs/DATABASE.md.

ALTER TABLE promo_financiacion
    ADD COLUMN red_medio_pago_id SMALLINT NULL REFERENCES medio_pago(id),
    ADD CONSTRAINT ck_promo_red_no_es_el_medio
        CHECK (red_medio_pago_id IS NULL OR red_medio_pago_id <> medio_pago_id);

DROP INDEX uq_promo_financiacion_natural;
CREATE UNIQUE INDEX uq_promo_financiacion_natural
    ON promo_financiacion (sitio_key, medio_pago_id, COALESCE(red_medio_pago_id, 0), tipo, alcance,
                           COALESCE(cuotas, 0), COALESCE(pct, 0));
