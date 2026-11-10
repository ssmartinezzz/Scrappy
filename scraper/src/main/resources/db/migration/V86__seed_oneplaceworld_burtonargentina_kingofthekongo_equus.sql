-- V86__seed_oneplaceworld_burtonargentina_kingofthekongo_equus.sql
--
-- Four own-label fashion stores, seed only: 'tiendanube' and 'vtex' are already valid plataforma values.
-- Measured 2026-11-10:
--   oneplaceworld    Tiendanube, /productos/ lists 71 (12 per page, 6 pages).
--   burtonargentina  Tiendanube, /productos/ lists 291 (50 per page, 6 pages); the sitemap names 559 product URLs.
--   kingofthekongo   Tiendanube, /productos/ lists 345 (12 per page, 29 pages); multi-country store, AR is the root.
--   equus            VTEX, men's-only (NormalizerService.GENERO_FIJO_POR_SITIO); the legacy API reports 2187.
-- rubro_forzado stays NULL so RubroResolver decides by category. Own label, so monomarca = true, and the marca
-- rows go in the same migration: NormalizerService assigns marca = sitio.nombre and the upsert does not create
-- `marca` rows (fk_productos_marca would reject every product, V52).
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Oneplaceworld',   'oneplaceworld',   'tiendanube', false, NULL, 'config'),
    ('Burtonargentina', 'burtonargentina', 'tiendanube', false, NULL, 'config'),
    ('Kingofthekongo',  'kingofthekongo',  'tiendanube', false, NULL, 'config'),
    ('Equus',           'equus',           'vtex',       false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true
 WHERE sitio_key IN ('oneplaceworld', 'burtonargentina', 'kingofthekongo', 'equus');

INSERT INTO marca (nombre) VALUES
    ('Oneplaceworld'), ('Burtonargentina'), ('Kingofthekongo'), ('Equus')
ON CONFLICT DO NOTHING;
