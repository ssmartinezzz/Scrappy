-- V116__seed_sommiercenter.sql — Sommier Center (sommiercenter.com), Magento 2: seed only. 'magento' is already a valid plataforma (V91).
--
-- Mattresses, sommiers, beds, headboards, pillows and sheets. Measured 2026-11-20 with MagentoPage over 10 department listings
-- (25 cards per page, ?p=N): 448 distinct products in 193 s. The departments overlap, MagentoPage dedups on the url slug.
-- rubro_por_defecto = 'hogar' and not rubro_forzado: a forced rubro would break the documented rollbacks of V24/V27/V45, which narrow that CHECK.
-- Own brands and licensed ones (Sealy, Meyer, Inducol, Telgo...) share the catalogue, so not monomarca.
-- No leaf mapping: a Magento listing carries no category id, so there is no sitio_categoria tree to curate. The names read as clothing
-- ("Sommier 1 Plaza y Media" is Medias, a "Combo ... + Almohadas" is Organización), so NormalizerService.SITIOS_SOLO_HOGAR keeps only a
-- home category from the name and sends the rest to Otros.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Sommiercenter', 'sommiercenter', 'magento', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'hogar' WHERE sitio_key = 'sommiercenter';
