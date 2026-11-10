-- V87__platform_vocabulary_kevingston.sql
--
-- Kevingston (www.kevingston.com) is a custom PHP storefront, not VTEX: its catalogue comes from a form-encoded
-- POST /api/v1/products (36 rows per page, 1577 products on 2026-11-10: 1105 hombres, 418 mujer, the rest
-- without a section). KevingstonPage reads it, so it needs its own platform value. Re-lists the full V76
-- domain plus 'kevingston' (CHECK, not a table, same as V6), then seeds the site.
-- Own label: monomarca = true and its marca row in the same migration (V52).
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory','ambiancedeco','hadriatica','kevingston'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Kevingston', 'kevingston', 'kevingston', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true WHERE sitio_key = 'kevingston';

INSERT INTO marca (nombre) VALUES ('Kevingston') ON CONFLICT DO NOTHING;
