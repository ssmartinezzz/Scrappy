-- V76__hadriatica_platform.sql
--
-- Hadriatica stays Tiendanube but gets its own page (HadriaticaPage): its listing is
-- server-rendered, so the base scroll + 1 s wait per page is skipped. Re-lists the full
-- V46 domain plus 'hadriatica', then moves the V74 row over.

ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory','ambiancedeco','hadriatica'));

UPDATE sitio SET plataforma = 'hadriatica' WHERE sitio_key = 'hadriatica';
