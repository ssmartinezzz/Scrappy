-- V125__seed_macstation.sql: Macstation, Odoo website_sale: seed only.
--
-- Macstation (www.macstation.com.ar), Apple reseller: Mac, iPad, iPhone, Watch, AirPods and third-party accessories (cases, cables, bags).
-- Measured 2026-11-20 (plain HTTP /shop/page/N, 20 cards per page, 47 pages): 932 cards read in 67 s, 930 products once the two
-- "10% / 20% de descuento tarjeta de crédito" pseudo-products ($1.21) are dropped by OdooPage.
-- rubro_por_defecto = 'tecnologia' and not rubro_forzado: a forced rubro would break the documented rollbacks of V24/V27/V45, which narrow that CHECK.
-- Not monomarca: Apple is most of the catalogue but cases, cables and bags are Targus, ItSkins, UAG, Adam Elements, Speck...
-- No leaf mapping: an Odoo listing carries no category id, so there is no sitio_categoria tree to curate; the name classifier decides.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Macstation', 'macstation', 'odoo', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'tecnologia' WHERE sitio_key = 'macstation';
