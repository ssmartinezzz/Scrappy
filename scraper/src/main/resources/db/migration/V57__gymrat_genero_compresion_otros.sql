-- V57__gymrat_genero_compresion_otros.sql
--
-- The /picks Gymrat section now splits Hombre/Mujer and gives compression tees their own card.
-- That needed three rules the code did not have, and this aligns the rows already stored (dev,
-- 2026-10-28: 410 of 1179 gymrat rows with no genero, ~50 compression tees, 89 gym rows in Otros)
-- instead of waiting for every site to be scraped again. The next scrape is the source of truth:
--   1. CategoryClassifier: tank/stringer/musculos -> Musculosa, boxy/long sleeve/slim fit/v-neck ->
--      Remera, croptop/bras -> Corpino, bandana -> Gorra. Only rows sitting in Otros move, and
--      sub_categoria resets to the abstention '' (it is resolved from (nombre, categoria)).
--   2. GenderResolver: wmn/girl/croptop/crop top/bras are women signals. Only rows with no genero
--      get one; a row that already says hombre or unisex keeps it.
--   3. SubcategoryResolver: compression Remera/Musculosa is sub_categoria 'compresión'.
-- Never a row locked by hand (V3). Three statements in Flyway's one transaction: atomic, and each is
-- idempotent because a handled row no longer matches its own WHERE.
UPDATE productos
   SET categoria     = CASE
                         WHEN n LIKE '% bandana %'                                   THEN 'Gorra'
                         WHEN n LIKE '% tank %' OR n LIKE '% stringer %'
                              OR n LIKE '% musculos %'                               THEN 'Musculosa'
                         WHEN n LIKE '% boxy %' OR n LIKE '% long sleeve %'
                              OR n LIKE '% slim fit %' OR n LIKE '% v-neck %'        THEN 'Remera'
                         ELSE 'Corpino'
                       END,
       sub_categoria = ''
  FROM (SELECT url AS u, ' ' || lower(btrim(nombre)) || ' ' AS n
          FROM productos
         WHERE categoria = 'Otros'
           AND bloqueado_por IS NULL) c
 WHERE url = c.u
   AND (c.n LIKE '% bandana %' OR c.n LIKE '% tank %' OR c.n LIKE '% stringer %'
        OR c.n LIKE '% musculos %' OR c.n LIKE '% boxy %' OR c.n LIKE '% long sleeve %'
        OR c.n LIKE '% slim fit %' OR c.n LIKE '% v-neck %' OR c.n LIKE '% croptop %'
        OR c.n LIKE '% crop top %' OR c.n LIKE '% bras %');

UPDATE productos
   SET genero = 'mujer'
 WHERE bloqueado_por IS NULL
   AND COALESCE(genero, '') = ''
   AND (' ' || lower(btrim(nombre)) || ' ') ~ ' (wmn|girl|croptop|crop top|bras) ';

UPDATE productos
   SET sub_categoria = 'compresión'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Remera', 'Musculosa')
   AND sub_categoria IS DISTINCT FROM 'compresión'
   AND (' ' || translate(lower(btrim(nombre)), 'áéíóúü', 'aeiouu') || ' ') ~ ' (compression|compresion) ';
