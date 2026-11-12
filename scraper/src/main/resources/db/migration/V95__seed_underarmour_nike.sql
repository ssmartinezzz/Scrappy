-- V95__seed_underarmour_nike.sql
--
-- Under Armour and Nike official stores, seed only: 'vtex' is already a valid plataforma value.
-- Measured 2026-11-12 (legacy API, header `resources`):
--   underarmour  VTEX, 394 products; tree/3 = Indumentaria(12), Calzado(6), Accesorios(8) under a root `UnderArmour`.
--   nike         VTEX, 2804 products, behind a Cloudflare WAF that 403s page.request() ("Attention Required",
--                no cf-mitigated header); the same call through fetch() inside the loaded page answers 206.
--                VtexPage falls back to that fetch on the first 403, no per-site flag.
-- Official monobrand stores, so monomarca = true: NormalizerService assigns marca = sitio.nombre, which is why
-- nombre is 'Under Armour' / 'Nike' (the spelling of BrandExtractor.MARCAS and SiteClassification) and not a
-- capitalised key. sitio_key is the normalised nombre (SiteClassification.sitioKey), so it is 'underarmour'.
-- Both 'Under Armour' and 'Nike' already exist in `marca` (V21), the INSERT below is a guard: the upsert does not
-- create `marca` rows and fk_productos_marca would reject every product (V52).
-- rubro_forzado stays NULL, as for Sporting: RubroResolver decides by category.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Under Armour', 'underarmour', 'vtex', false, NULL, 'config'),
    ('Nike',         'nike',        'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET monomarca = true WHERE sitio_key IN ('underarmour', 'nike');

INSERT INTO marca (nombre) VALUES ('Under Armour'), ('Nike')
ON CONFLICT DO NOTHING;
