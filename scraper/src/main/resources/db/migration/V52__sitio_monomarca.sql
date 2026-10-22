-- V52__sitio_monomarca.sql
--
-- An own-label store has no brand to extract: the brand IS the store. V19 made
-- BrandExtractor abstain instead of falling back to the site name, which is right
-- for a multibrand store and wrong here — every product of these stores ended up
-- with marca NULL, so /marcas and the catalog search could never find them.
--
-- The flag is data on `sitio`, not a list in Java (CODE-6): NormalizerService reads
-- it through SiteRegistry and assigns marca = sitio.nombre. Zentra is NOT flagged
-- (it resells INPRO's catalog); neither is any multibrand or technology store.
--
-- Idempotent on purpose: V52SitioMonomarcaTest replays this file over rows inserted
-- after it ran to prove the backfill.

ALTER TABLE sitio ADD COLUMN IF NOT EXISTS monomarca boolean NOT NULL DEFAULT false;

UPDATE sitio SET monomarca = true WHERE sitio_key IN (
    'freres',
    'fuark',
    'mmartinez',
    'monkyforce',
    'harvey',
    'bulks',
    'bullbenny',
    'batuk',
    'tussy',
    'foreverbstrd',
    'forever',
    'fursten',
    'barnes',
    'vcp',
    'eldon',
    'midway',
    'inpro',
    'flowin',
    'vayzenhome',
    'mrplacadeco',
    'ambiancedeco'
);

-- fk_productos_marca: the upsert does not get-or-create `marca`, so a brand missing
-- here would make sp_upsert_run reject the row and the scrape would read "0 nuevos".
INSERT INTO marca (nombre)
SELECT nombre FROM sitio WHERE monomarca
ON CONFLICT DO NOTHING;

-- Same lock rule as sp_upsert_run: a row locked by hand (bloqueado_por) keeps its marca.
UPDATE productos p
SET marca = s.nombre
FROM sitio s
WHERE s.monomarca
  AND p.sitio_key = s.sitio_key
  AND p.bloqueado_por IS NULL
  AND p.marca IS DISTINCT FROM s.nombre;
