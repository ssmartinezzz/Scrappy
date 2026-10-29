-- V58__genero_senal_nombre_gana.sql
--
-- GenderResolver now lets a women word in the product NAME beat the site's own genero tag, because
-- collection-based tags (Monkyforce) file women's garments under hombre/unisex. This aligns the rows
-- already stored (dev, 2026-10-29: 42 rows, 30 with no genero, 7 hombre, 5 unisex) instead of waiting
-- for every site to be scraped again. The next scrape is the source of truth; known false positives
-- are "Antiparras ... Wonder Woman" and "Colchoneta ... Girl Fit".
-- Rule (identical to GenderResolver): a women word and no men word in the accent-stripped name, whole
-- words only. Infantil rows are skipped. Never a row locked by hand (V3). Idempotent: a handled row is
-- already 'mujer' and no longer matches its own WHERE.
UPDATE productos
   SET genero = 'mujer'
 WHERE bloqueado_por IS NULL
   AND genero IS DISTINCT FROM 'mujer'
   AND COALESCE(genero, '') <> 'infantil'
   AND (' ' || translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun') || ' ')
       ~ '[^a-z0-9](wmn|girl|croptop|crop top|bras|woman|women|mujer|mujeres|dama|damas|femenino|calza|calzas)[^a-z0-9]'
   AND (' ' || translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun') || ' ')
       !~ '[^a-z0-9](hombre|hombres|men|mens|masculino|caballero|caballeros|varon|varones)[^a-z0-9]'
   AND translate(lower(nombre), 'áéíóúüñ', 'aeiouun') !~ '(nino|nina|kids|infantil|bebe)';
