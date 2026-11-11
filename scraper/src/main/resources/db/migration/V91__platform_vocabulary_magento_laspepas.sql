-- V91__platform_vocabulary_magento_laspepas.sql
--
-- Las Pepas (laspepas.com.ar) is women's fashion on Magento 2, the first store of that platform. MagentoPage reads its
-- server-rendered listing (12 cards per page, ?p=N), so it needs its own platform value. Re-lists the full V87 domain
-- plus 'magento' (CHECK, not a table, same as V6), then seeds the site.
-- rubro_forzado stays NULL so RubroResolver decides by category (the store also sells perfume, V90). Own label, so
-- monomarca = true and its marca row in the same migration (V52).
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory','ambiancedeco','hadriatica','kevingston','magento'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Laspepas', 'laspepas', 'magento', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true WHERE sitio_key = 'laspepas';

INSERT INTO marca (nombre) VALUES ('Laspepas') ON CONFLICT DO NOTHING;
