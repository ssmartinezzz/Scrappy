-- V85__deporte_snowboard.sql
--
-- New sport 'snowboard' (Burton Argentina, DC Shoes snow gear). SportTaxonomy lists it right after futbol
-- and BEFORE natacion: Burton's snow goggles are named "Lentes Para Antiparras Anon M5" and "antiparra"
-- is a natacion keyword, so the first-match order is what keeps them out of swimming.
-- Adding a sport is a row here plus an entry in SportTaxonomy (V59). Domain = the `deporte` table.
INSERT INTO deporte (codigo, nombre) VALUES ('snowboard', 'Snowboard')
ON CONFLICT (codigo) DO NOTHING;

-- Backfill mirroring SportResolver (accent-stripped name, punctuation as space, whole keywords) and the
-- keyword list of SportTaxonomy 'snowboard'. It touches rows with no sport and rows already filed as
-- natacion (the Anon goggles); a row with any other sport keeps it. Botines stay football. Only clothing and
-- sports rubros (NULL/blank = indumentaria), never a row locked by hand (V3).
-- Idempotent: a handled row is already 'snowboard' and no longer matches its WHERE.
WITH candidatas AS (
    SELECT url,
           (' ' || regexp_replace(translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun'), '[^a-z0-9]+', ' ', 'g') || ' ') AS t
      FROM productos
     WHERE bloqueado_por IS NULL
       AND (deporte IS NULL OR deporte = 'natacion')
       AND categoria IS DISTINCT FROM 'Botines'
       AND COALESCE(NULLIF(rubro, ''), 'indumentaria') IN ('indumentaria', 'deporte')
)
UPDATE productos p
   SET deporte = 'snowboard'
  FROM candidatas c
 WHERE p.url = c.url
   AND c.t ~ ' (snowboard|snowboards|snowboarding|snow|ski|skis|esqui|anon) ';
