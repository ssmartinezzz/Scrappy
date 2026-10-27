-- V54__seed_baesics.sql
--
-- Baesics: own-label gym clothing, genuine Shopify (/products.json, 56 products
-- on page 1, measured 2026-10-27). Seed only: 'shopify' is already a valid
-- plataforma. Own label, so monomarca = true, and its marca row goes in the
-- same migration — the upsert does not create `marca` rows (see V52).
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Baesics', 'baesics', 'shopify', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true WHERE sitio_key = 'baesics';

INSERT INTO marca (nombre) VALUES ('Baesics') ON CONFLICT DO NOTHING;
