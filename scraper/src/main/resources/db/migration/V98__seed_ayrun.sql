-- V98__seed_ayrun.sql
--
-- Ayrun: own-label women's gym clothing on Tiendanube (/productos/, 151 products in 13 pages of 12,
-- measured 2026-11-12). Seed only: 'tiendanube' is already a valid plataforma. Own label, so
-- monomarca = true, and its marca row goes in the same migration: the upsert does not create
-- `marca` rows (see V52). The gymrat tag and the fixed 'mujer' gender are code, not data
-- (SiteClassification.GYM_SITIOS, NormalizerService.GENERO_FIJO_POR_SITIO).
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Ayrun', 'ayrun', 'tiendanube', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true WHERE sitio_key = 'ayrun';

INSERT INTO marca (nombre) VALUES ('Ayrun') ON CONFLICT DO NOTHING;
