-- V60__infantil_sufijos.sql
--
-- GenderResolver only saw nino/nina/kids/infantil/bebe as substrings, so kids' items sized with a
-- suffix (Jr, Junior, Kid, Youth, PS, GS, TD, Toddler) kept their adult or blank genero and reached
-- the builders (dev, 2026-11-02: ~126 rows). The resolver now also matches those as whole words; this
-- aligns the rows already stored instead of waiting for every site to be scraped again. The next
-- scrape is the source of truth.
-- Rule (identical to GenderResolver): accent-stripped name, punctuation as space, whole word of
-- jr|junior|kid|kids|youth|ps|gs|td|toddler. Whole words only: "Boca Juniors" is not "junior".
-- Only clothing (NULL/blank rubro = indumentaria): "PS" is also a console. Active or not, never a row
-- locked by hand (V3). Idempotent: a handled row is already 'infantil' and no longer matches its WHERE.
UPDATE productos
   SET genero = 'infantil'
 WHERE bloqueado_por IS NULL
   AND genero IS DISTINCT FROM 'infantil'
   AND COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
   AND (' ' || regexp_replace(translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun'), '[^a-z0-9]+', ' ', 'g') || ' ')
       ~ ' (jr|junior|kid|kids|youth|ps|gs|td|toddler) ';
