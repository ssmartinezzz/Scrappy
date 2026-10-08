-- V43__seed_flowin_and_prestashop_armytech.sql — add-flowin-armytech
--
-- Flowin: Shopify genuino (/products.json, 8 productos en una página), rubro
-- oficina. Sólo seed; 'shopify' y 'oficina' ya son válidos.
--
-- Armytech: PrestaShop 1.7, plataforma nueva 'prestashop' con page propia
-- (PrestashopPage). Se re-lista el dominio COMPLETO de `sitio_plataforma_check`
-- tal como lo dejó V28 (13 valores) más 'prestashop': DROP + ADD sin rebasear
-- borraría los valores anteriores. `chk_productos_rubro_domain` y
-- `sitio_rubro_forzado_check` no se tocan, 'tecnologia' y 'oficina' ya entraron.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop','prestashop'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Flowin',   'flowin',   'shopify',    false, 'oficina',    'config'),
    ('Armytech', 'armytech', 'prestashop', false, 'tecnologia', 'config')
ON CONFLICT (nombre) DO NOTHING;
