-- V121__platform_vocabulary_sfcc.sql
--
-- Dexter and Moov (the same sports retail group) run Salesforce Commerce Cloud (Demandware). SfccPage reads the
-- Search-UpdateGrid tiles, so the platform needs its own value. Re-lists the full V91 domain plus 'sfcc' (CHECK, not a
-- table, same as V6). The sites are seeded by V122 and V123.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory','ambiancedeco','hadriatica','kevingston','magento','sfcc'));
