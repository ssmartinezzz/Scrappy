-- V132__subcategoria_bicicleta.sql
--
-- Gives sub_categoria to 'Bicicleta' by BIKE TYPE, in the words Argentine stores use: Mountain Bike, Paseo y Playera, Ruta,
-- Urbana / Fixie, BMX, Plegable, Eléctrica, Infantil, Triciclo and Camicleta. Every name leads with bicicleta, so the type comes from an
-- explicit word of the name; only when there is none does the RODADO decide (R27.5 and R29 are Mountain Bike, R12 to R16 and a R18 / R20
-- without an adult signal are Infantil). A stationary bike (fija, spinning, indoor, magnética) is exercise equipment, not a bike type: its
-- rule has an empty sub, so no later rule gives it one. Accessories that lead with another word (candado, inflador, casco, bolso,
-- caramañola, kit) never match a leader and keep an empty sub, as does a name no rule claims.
--
-- Same rule as BicicletaSubcategoryResolver (Java), normalized exactly as in V128 to V131. V132BicicletaAgreementTest runs both over the
-- same names. One single-line text blob, rows 'cat|sub|leaders|any|veto' joined by ';' and words by ',' (no raw newline: Sonar flags one
-- inside a literal); the first row that fires wins: the name must START with a leader, contain one of 'any' when present and none of
-- 'veto'. Rows locked by hand (bloqueado_por) are not touched; active and inactive rows alike; only a row whose sub changes is written.
-- SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE.
SET CONSTRAINTS ALL IMMEDIATE;

WITH reglas AS (
  SELECT split_part(f.fila, '|', 2) AS etiqueta, f.orden,
         string_to_array(split_part(f.fila, '|', 3), ',') AS inicios,
         string_to_array(NULLIF(split_part(f.fila, '|', 4), ''), ',') AS contiene,
         string_to_array(NULLIF(split_part(f.fila, '|', 5), ''), ',') AS excluye
    FROM unnest(string_to_array('bi|Camicleta| camicleta||;bi|Camicleta| bicicleta, bici , bicis | sin pedales , equilibrio , balance bike |;bi|Triciclo| triciclo||;bi|Eléctrica| bicicleta, bici , bicis | electrica , electrico |;bi|Plegable| bicicleta, bici , bicis | plegable|;bi|BMX| bicicleta, bici , bicis | bmx , freestyle , stunt , dirt jump |;bi|| bicicleta, bici , bicis | fija , spinning , indoor , estatica , magnetica , horizontal , pedalera |;bi|Infantil| bicicleta, bici , bicis | infantil , infanti , nena , nene , nina , nino, kids , rueditas , bebe, junior |;bi|Infantil| bicicleta, bici , bicis | r12, r 12, rodado 12, rod 12, rod. 12, rod12, r13, r 13, rodado 13, rod 13, rod. 13, rod13, r14, r 14, rodado 14, rod 14, rod. 14, rod14, r16, r 16, rodado 16, rod 16, rod. 16, rod16, bicicleta 12, bicicleta 14, bicicleta 16|;bi|Ruta| bicicleta, bici , bicis | ruta , carrera , speed , gravel , 700c, ciclocross |;bi|Urbana / Fixie| bicicleta, bici , bicis | urbana, urban , fixie, fixed , ciudad |;bi|Mountain Bike| bicicleta, bici , bicis | mtb , mountain , moutain , montana , fat bike |;bi|Paseo y Playera| bicicleta, bici , bicis | playera, paseo, vintage , classic, retro , city |;bi|Mountain Bike| bicicleta, bici , bicis | r29, r 29, rodado 29, rod 29, rod. 29, rod29, r27, r 27, rodado 27, rod 27, rod. 27, rod27, bicicleta 29, bicicleta 27|;bi|Infantil| bicicleta, bici , bicis | r18, r 18, rodado 18, rod 18, rod. 18, rod18, r20, r 20, rodado 20, rod 20, rod. 20, rod20, bicicleta 20| dama , lady , mujer , adulto , hombre , varon , plegable', ';')) WITH ORDINALITY AS f(fila, orden)
),
textos AS (
  SELECT b.url, b.cuerpo,
         regexp_replace(b.cuerpo, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS arranque
    FROM (
      SELECT p.url,
             ' ' || btrim(regexp_replace(translate(lower(p.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS cuerpo
        FROM productos p
       WHERE p.bloqueado_por IS NULL
         AND p.categoria = 'Bicicleta'
    ) b
),
candidatas AS (
  SELECT t.url, r.etiqueta, row_number() OVER (PARTITION BY t.url ORDER BY r.orden) AS puesto
    FROM textos t
    JOIN reglas r
      ON EXISTS (SELECT 1 FROM unnest(r.inicios) w WHERE left(t.arranque, length(w)) = w)
     AND (r.contiene IS NULL OR EXISTS (SELECT 1 FROM unnest(r.contiene) w WHERE position(w IN t.cuerpo) > 0))
     AND (r.excluye IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(r.excluye) w WHERE position(w IN t.cuerpo) > 0))
)
UPDATE productos AS pr
   SET sub_categoria = COALESCE(c.etiqueta, '')
  FROM textos t
  LEFT JOIN candidatas c ON c.url = t.url AND c.puesto = 1
 WHERE pr.url = t.url
   AND COALESCE(pr.sub_categoria, '') <> COALESCE(c.etiqueta, '');
