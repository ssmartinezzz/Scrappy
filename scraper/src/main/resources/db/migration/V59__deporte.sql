-- V59__deporte.sql
--
-- Sport-specific merch (club kits, swimwear, hockey...) sat in general clothing categories and fed
-- the builders and recommendations. `deporte` is a dimension next to genero/rubro, not a category: a
-- Boca shirt stays Remera and gains deporte='futbol'. Domain = the `deporte` table (3NF); "no sport"
-- is NULL, never a sentinel (abstention is not an FK value). Adding a sport is a row here plus an
-- entry in SportTaxonomy.
--
-- The backfill mirrors SportResolver (accent-stripped name, punctuation as space, whole keywords,
-- first sport in SportTaxonomy order wins, Botines is football) so the stored catalog is aligned
-- instead of waiting for every site to be scraped again. The next scrape is the source of truth.
-- Only clothing (NULL/blank rubro = indumentaria), never a row locked by hand (V3), never a row that
-- already has a sport. The whole file is re-runnable: IF NOT EXISTS / ON CONFLICT DO NOTHING / the
-- backfill only touches rows still without a sport.
CREATE TABLE IF NOT EXISTS deporte (
    codigo TEXT PRIMARY KEY,
    nombre TEXT NOT NULL
);

INSERT INTO deporte (codigo, nombre) VALUES
    ('futbol',   'Fútbol'),
    ('natacion', 'Natación'),
    ('hockey',   'Hockey'),
    ('rugby',    'Rugby'),
    ('tenis',    'Tenis y pádel'),
    ('basquet',  'Básquet'),
    ('voley',    'Vóley'),
    ('ciclismo', 'Ciclismo'),
    ('golf',     'Golf'),
    ('combate',  'Combate')
ON CONFLICT (codigo) DO NOTHING;

ALTER TABLE productos ADD COLUMN IF NOT EXISTS deporte TEXT NULL REFERENCES deporte (codigo);

CREATE INDEX IF NOT EXISTS idx_productos_deporte ON productos (deporte) WHERE deporte IS NOT NULL;

WITH candidatas AS (
    SELECT url, categoria,
           (' ' || regexp_replace(translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun'), '[^a-z0-9]+', ' ', 'g') || ' ') AS t
      FROM productos
     WHERE bloqueado_por IS NULL
       AND deporte IS NULL
       AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
), clasificadas AS (
    SELECT url,
           CASE
                WHEN categoria = 'Botines' THEN 'futbol'
                WHEN t ~ ' (futbol|camiseta de futbol|botin|botines|tachones|arquero|boca juniors|river plate|racing club|independiente|san lorenzo|estudiantes|velez|newells|rosario central|talleres|belgrano|huracan|lanus|banfield|gimnasia y esgrima|argentinos juniors|seleccion argentina|afa|real madrid|fc barcelona|barcelona fc|barca|manchester united|manchester city|juventus|psg|paris saint germain|inter miami|milan|bayern|liverpool|chelsea|arsenal) ' THEN 'futbol'
                WHEN t ~ ' (natacion|malla de natacion|antiparra|antiparras|gorra de natacion|gorro de natacion|swim|swimwear|aleta|aletas|speedo) ' THEN 'natacion'
                WHEN t ~ ' (hockey) ' THEN 'hockey'
                WHEN t ~ ' (rugby) ' THEN 'rugby'
                WHEN t ~ ' (tenis|padel|paddle|raqueta|raquetas) ' THEN 'tenis'
                WHEN t ~ ' (basquet|basquetbol|basket|basketball|nba) ' THEN 'basquet'
                WHEN t ~ ' (voley|voleibol|volley|volleyball) ' THEN 'voley'
                WHEN t ~ ' (ciclismo|ciclista|ciclistas|maillot|mtb) ' THEN 'ciclismo'
                WHEN t ~ ' (golf) ' THEN 'golf'
                WHEN t ~ ' (boxeo|guantes de box|guantes de boxeo|vendas de boxeo|bolsa de boxeo|kickboxing|kick boxing|mma|muay thai|judo|karate|taekwondo|jiu jitsu|bjj) ' THEN 'combate'
           END AS codigo
      FROM candidatas
)
UPDATE productos p
   SET deporte = c.codigo
  FROM clasificadas c
 WHERE p.url = c.url
   AND c.codigo IS NOT NULL;
