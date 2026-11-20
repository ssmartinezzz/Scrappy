-- V124__platform_vocabulary_odoo.sql
--
-- Macstation and Gentech run Odoo website_sale. OdooPage reads the server-rendered /shop listing, so the platform needs its
-- own value. Re-lists the full V121 domain plus 'odoo' (CHECK, not a table, same as V6). The sites are seeded by V125 and V126.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory','ambiancedeco','hadriatica','kevingston','magento','sfcc','odoo'));
