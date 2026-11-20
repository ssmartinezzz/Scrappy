-- V115__seed_somosrex.sql — Somos Rex (somosrex.com), Magento 2: seed only. 'magento' is already a valid plataforma (V91).
--
-- Hardware, paint and home store: paints and sealants (~3,100 of ~5,200 listed), tools, appliances, garden, lighting, furniture.
-- Measured 2026-11-20 with MagentoPage over 13 department listings (24 cards per page, ?p=N, price read from
-- data-price-type="defaultPromoPrice"): 4,584 distinct products in 1,720 s (29 min). The departments overlap, MagentoPage dedups on the url slug.
-- Mixed catalogue: rubro_forzado stays NULL and rubro_por_defecto = 'construccion', the rubro of the paints and tool lines the name
-- classifier leaves as Otros (after the rules below 3,939 rows are construccion, 623 hogar, 22 deporte).
-- Not rubro_forzado: a forced rubro would break the documented rollbacks of V24/V27/V45, which narrow that CHECK.
-- No leaf mapping: a Magento listing carries no category id, so there is no sitio_categoria tree to curate. The classifier is the only
-- signal, and it read paints by weight as food ("Esmalte 400 ml", "Weber 20 Kg" -> Alimentos, rubro suplementos) and a roller by its
-- wool as a sweater: NormalizerService.SITIOS_SOLO_HOGAR_Y_OBRA keeps only a home, building or sports category from the name, and
-- the paint and sealant brand lines that lead a name ("Cetol", "Recuplast Frentes", "Vadex") are keywords of the existing categories.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Somosrex', 'somosrex', 'magento', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'construccion' WHERE sitio_key = 'somosrex';
