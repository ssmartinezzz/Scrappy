-- V44__seed_gamerfactory.sql — site-gamerfactory
--
-- Gamer Factory: SPA React propia sobre una API JSON pública, plataforma nueva
-- 'gamerfactory' con page propia (GamerFactoryPage). Se re-lista el dominio
-- COMPLETO de `sitio_plataforma_check` tal como lo dejó V43 (14 valores) más
-- 'gamerfactory': DROP + ADD sin rebasear borraría los valores anteriores.
-- `chk_productos_rubro_domain` y `sitio_rubro_forzado_check` no se tocan,
-- 'tecnologia' ya es válido.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop',
                          'gamerfactory'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Gamerfactory', 'gamerfactory', 'gamerfactory', false, 'tecnologia', 'config')
ON CONFLICT (nombre) DO NOTHING;
