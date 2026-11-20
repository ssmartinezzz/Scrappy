-- V126__seed_gentech.sql: Gentech, Odoo website_sale: seed only.
--
-- Gentech (www.gentech.com.ar), a single supplement brand (GenTech / AFA): whey, creatine, amino acids, bars, gels, fat burners, shakers.
-- Measured 2026-11-20 (plain HTTP /shop/page/N with Accept-Language es-AR, 21 cards per page, 3 pages): 45 products in 3 s.
-- Own-brand store, so monomarca = true (NormalizerService assigns marca = sitio.nombre; the names ("Whey Protein AFA 1kg", "Iron Gel Turbo AFA") rarely say
-- Gentech) and 'Gentech' must be a `marca` row, or fk_productos_marca (V52) rejects every product and the scrape reads "0 nuevos".
-- rubro_por_defecto = 'suplementos' and not rubro_forzado: a forced rubro would break the documented rollbacks of V24/V27/V45, which narrow that CHECK.
-- No leaf mapping: an Odoo listing carries no category id, so the name classifier decides.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Gentech', 'gentech', 'odoo', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true, rubro_por_defecto = 'suplementos' WHERE sitio_key = 'gentech';

INSERT INTO marca (nombre) VALUES ('Gentech')
ON CONFLICT DO NOTHING;
