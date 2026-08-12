-- V18__sitio_lookup_table.sql — close-1nf-and-3nf-foundation
-- (spec "catalog-site-registry", design DD3/DD4/DD5)
--
-- A single source of site identity. Two name columns, on purpose: `nombre`
-- is the display form ScraperFactory.crear stores ("Harvey", "Fullh4rd"),
-- `sitio_key` is SiteClassification.sitioKey()'s normalized form ("harvey")
-- — they are genuinely different values (case, punctuation), and one column
-- would re-create the exact mismatch this table exists to stop hiding.
--
-- CHECK, not lookup tables, for plataforma/rubro_forzado/origen — 9, 2 and 3
-- values respectively. V6's criterion (small closed domain -> CHECK) applies;
-- V13's inversion (81 values -> table) does not.
--
-- origen exists because a row reachable only from SELECT DISTINCT sitio FROM
-- productos is a historical artifact (a renamed or retired store), not a
-- site scraped today. Without it the seed's UNION is irreversible
-- information loss. No `activo` column — that flag lives in
-- config.properties/sitios_dinamicos, and copying it would be a sixth
-- taxonomy (CODE-6).
--
-- Seeded but READ BY NOTHING in this slice (design DD3) — es_premium,
-- rubro_forzado and the name-set half of plataforma all still live in
-- SiteClassification/ScraperFactory. SitioSeedSyncTest (classpath only, no
-- DB) asserts the seed is a mirror of those, bidirectionally, so the five
-- drifted copies this table replaces cannot drift again without a red build.

CREATE TABLE sitio (
    nombre        TEXT PRIMARY KEY,
    sitio_key     TEXT NOT NULL UNIQUE,
    plataforma    TEXT NOT NULL
        CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                              'monkyforce','maximus','fullh4rd','compragamer')),
    es_premium    BOOLEAN NOT NULL DEFAULT false,
    rubro_forzado TEXT NULL
        CHECK (rubro_forzado IN ('tecnologia','suplementos')),
    origen        TEXT NOT NULL
        CHECK (origen IN ('config','dinamico','historico'))
);

-- Seed from config.properties (origen='config') — every active `sitio.X.url`
-- entry, as of this migration. plataforma mirrors ScraperFactory.crear's 8
-- name-sets (default 'tiendanube'); es_premium mirrors SITIOS_PREMIUM;
-- rubro_forzado mirrors TECH_SITIOS/SUPPL_SITIOS' bare-name entries — the
-- domain-suffixed variants (compragamer.com, etc.) collapse onto the same
-- sitio_key so they need no separate row.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Freres',       'freres',       'shopify',     false, NULL,          'config'),
    ('Harvey',       'harvey',       'tiendanube',  true,  NULL,          'config'),
    ('Midway',       'midway',       'tiendanube',  false, NULL,          'config'),
    ('Batuk',        'batuk',        'tiendanube',  false, NULL,          'config'),
    ('Tussy',        'tussy',        'tiendanube',  false, NULL,          'config'),
    ('Bulks',        'bulks',        'tiendanube',  false, NULL,          'config'),
    ('Bullbenny',    'bullbenny',    'tiendanube',  false, NULL,          'config'),
    ('Barnes',       'barnes',       'tiendanube',  false, NULL,          'config'),
    ('Vcp',          'vcp',          'shopify',     false, NULL,          'config'),
    ('Sporting',     'sporting',     'vtex',        false, NULL,          'config'),
    ('Vaypol',       'vaypol',       'vaypol',      false, NULL,          'config'),
    ('City',         'city',         'vaypol',      false, NULL,          'config'),
    ('Dcshoes',      'dcshoes',      'woocommerce', false, NULL,          'config'),
    ('Eldon',        'eldon',        'tiendanube',  false, NULL,          'config'),
    ('Maximus',      'maximus',      'maximus',     false, 'tecnologia',  'config'),
    ('Fullh4rd',     'fullh4rd',     'fullh4rd',    false, 'tecnologia',  'config'),
    ('Compragamer',  'compragamer',  'compragamer', false, 'tecnologia',  'config'),
    ('Entreno',      'entreno',      'tiendanube',  false, 'suplementos', 'config'),
    ('Fuark',        'fuark',        'tiendanube',  false, NULL,          'config'),
    ('Monkyforce',   'monkyforce',   'monkyforce',  false, NULL,          'config'),
    ('Fursten',      'fursten',      'tiendanube',  false, NULL,          'config'),
    ('Forever',      'forever',      'shopify',     false, NULL,          'config'),
    ('Foreverbstrd', 'foreverbstrd', 'tiendanube',  false, NULL,          'config');

-- Absorbed from sitios_dinamicos (dashboard-added sites) — origen='dinamico'.
-- ON CONFLICT DO NOTHING: a site already seeded from config keeps that row.
-- plataforma is normalized against the same closed domain the CHECK enforces
-- — POST /api/sitios accepts a client-supplied string with no server-side
-- validation against this vocabulary, so an untrusted value here must not be
-- able to abort the whole migration.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
SELECT DISTINCT
    d.nombre,
    lower(regexp_replace(d.nombre, '[^a-zA-Z0-9]', '', 'g')),
    CASE WHEN d.plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                                'monkyforce','maximus','fullh4rd','compragamer')
         THEN d.plataforma ELSE 'tiendanube' END,
    false,
    NULL,
    'dinamico'
FROM sitios_dinamicos d
WHERE d.nombre IS NOT NULL AND btrim(d.nombre) <> ''
ON CONFLICT (nombre) DO NOTHING;

-- Absorbed from productos.sitio (a name reachable only through scraped
-- rows — a renamed or retired store) — origen='historico'. No platform
-- signal is available for these, so they default to 'tiendanube' like any
-- unmatched name would in ScraperFactory.crear.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
SELECT DISTINCT
    p.sitio,
    lower(regexp_replace(p.sitio, '[^a-zA-Z0-9]', '', 'g')),
    'tiendanube',
    false,
    NULL,
    'historico'
FROM productos p
WHERE p.sitio IS NOT NULL AND btrim(p.sitio) <> ''
ON CONFLICT (nombre) DO NOTHING;

-- foreverbstrd rubro fix (design DD5) — NOT cosmetic. Removing foreverbstrd
-- from TECH_SITIOS (below, RubroResolver/SiteClassification) is not
-- self-healing: RubroResolver.resolver falls through to `rubroExistente` for
-- a non-textile, non-supplement product once no site/category rule forces
-- a rubro, and a row re-fed from the DB snapshot (fromDBParcial) carries the
-- old sticky 'tecnologia' forever. The category list mirrors
-- CategoryGroups.esIndumentariaOCalzado exactly (INDUMENTARIA_O_CALZADO_EXTRA
-- union CATEGORIAS_CALZADO) — "non-textile" means NOT IN this list.
UPDATE productos
SET rubro = 'indumentaria'
WHERE sitio = 'Foreverbstrd'
  AND rubro = 'tecnologia'
  AND categoria NOT IN (
    'Puffer','Campera','Sweater','Buzo','Musculosa','Camisa','Remera',
    'Chomba','Casaca','Chaleco','Saco','Traje','Piloto',
    'Calza','Baggy','Jean','Jogging','Short','Bermuda','Pollera',
    'Vestido','Enterito','Pantalón',
    'Calzoncillos','Corpino','Malla',
    'Mochila','Bolso','Riñonera','Billetera','Cinturón',
    'Bufanda','Guantes','Gorro','Gorra','Lentes','Medias',
    'Accesorio Deportivo',
    'Zapatilla Running','Zapatilla Entrenamiento','Zapatilla Skate',
    'Zapatilla Urbana','Zapatilla','Sneaker',
    'Botines','Borcego','Botas','Ojotas','Sandalia','Mocasin','Zapato','Pantufla'
  );
