-- V130__subcategoria_ropa_de_cama_colchon.sql
--
-- Gives sub_categoria to 'Ropa de Cama' (Juego de Sábanas, Acolchado, Cubrecama, Almohada, Frazada y Manta...) and to 'Colchón'. A piece
-- of bedding is read from its LEADING noun, as in V128 and V129. A mattress always leads with colchón or sommier, so its sub is the SIZE
-- the way Argentine stores sell beds ('1 Plaza', '1 1/2 Plaza', '2 Plazas', '2 1/2 Plazas', 'Queen', 'King'), read from the plazas
-- written in the name or from the dimensions stores print instead (80x190, 140 x 190, 1.60x0.24x2.00). Sommier and colchón of the same
-- size get the same sub. A name no rule claims keeps an empty sub: that includes the protectors, bases and beds that sit in Colchón.
--
-- Same rule as CamaSubcategoryResolver (Java), normalized exactly as in V128 / V129 (accents and / : , ; + ( ) [ ] stripped, blanks
-- collapsed, the sale prefix skipped). V130CamaAgreementTest runs both over the same names.
--
-- One single-line text blob, rows 'cat|sub|leaders|any|veto' joined by ';' and words by ',' (no raw newline: Sonar flags one inside a
-- literal). The first row of the category that fires wins: the name must START with a leader, contain one of 'any' when present and none
-- of 'veto'. Rows locked by hand (bloqueado_por) are not touched; active and inactive rows alike; only a row whose sub changes is written.
-- SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE.
SET CONSTRAINTS ALL IMMEDIATE;

UPDATE productos AS prod
   SET sub_categoria = elegido.sub
  FROM (
    SELECT DISTINCT ON (n.url) n.url, COALESCE(v.sub, '') AS sub
      FROM (
        SELECT q.url, q.clave, q.texto,
               regexp_replace(q.texto, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS inicio
          FROM (
            SELECT c.url, CASE c.categoria WHEN 'Colchón' THEN 'co' ELSE 'rc' END AS clave,
                   ' ' || btrim(regexp_replace(translate(lower(c.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS texto
              FROM productos c
             WHERE c.bloqueado_por IS NULL
               AND c.categoria IN ('Ropa de Cama', 'Colchón')
          ) q
      ) n
      LEFT JOIN LATERAL (
        SELECT split_part(f.fila, '|', 2) AS sub, f.orden
          FROM unnest(string_to_array('rc|Juego de Sábanas| juego de sabana, juego sabana, juego de cama , set de sabana, sabana||;rc|Acolchado| acolchado||;rc|Cubrecama| cubrecama, cubre cama, cover , quilt ||;rc|Protector| protector, cubrecolchon, cubre colchon, cubresommier, cubre sommier||;rc|Protector| funda , fundas | colchon , sommier |;rc|Almohada| almohada , almohadas ||;rc|Funda| funda , fundas || silla , sillas , sillon , sofa ;rc|Frazada y Manta| frazada, manta , mantas , cobertor, piecera, pie de cama||;rc|Plumón| plumon, edredon, nordico, duvet ||;co|1 1/2 Plaza| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 100x190, 100 x 190, 100x200, 100 x 200, 1.00x, 1 plaza y media, plaza y media, 1 1 2 plaza, 1.5 plaza, 1.5 pz, 1 5 plaza|;co|2 1/2 Plazas| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 150x190, 150 x 190, 150x200, 150 x 200, 1.50x, 1.50 , 2 1 2 plaza, 2.5 plaza, 2 plazas y media, 2 plaza y media|;co|1 Plaza| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 80x190, 80 x 190, 080x190, 080 x 190, 80x200, 80 x 200, 080x200, 080 x 200, 90x190, 90 x 190, 090x190, 090 x 190, 90x200, 90 x 200, 090x200, 090 x 200, 0.80x, 0.90x, 0.80 , 0.90 , 1 plaza|;co|2 Plazas| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 130x190, 130 x 190, 130x200, 130 x 200, 140x190, 140 x 190, 140x200, 140 x 200, 1.30x, 1.40x, 1.30 , 1.40 , 130c190, 2 plaza|;co|Queen| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 160x190, 160 x 190, 160x200, 160 x 200, 1.60x, 1.60 , queen |;co|King| colchon , colchones , sommier , sommiers , conjunto , conj , bicama | 180x190, 180 x 190, 180x200, 180 x 200, 200x190, 200 x 190, 200x200, 200 x 200, 1.80x, 1.80 , 2.00x, king size , super king |', ';')) WITH ORDINALITY AS f(fila, orden)
         WHERE split_part(f.fila, '|', 1) = n.clave
           AND EXISTS (SELECT 1 FROM unnest(string_to_array(split_part(f.fila, '|', 3), ',')) l WHERE left(n.inicio, length(l)) = l)
           AND (split_part(f.fila, '|', 4) = ''
                OR EXISTS (SELECT 1 FROM unnest(string_to_array(split_part(f.fila, '|', 4), ',')) a WHERE position(a IN n.texto) > 0))
           AND (split_part(f.fila, '|', 5) = ''
                OR NOT EXISTS (SELECT 1 FROM unnest(string_to_array(split_part(f.fila, '|', 5), ',')) x WHERE position(x IN n.texto) > 0))
         ORDER BY f.orden
         LIMIT 1
      ) v ON true
     ORDER BY n.url
  ) elegido
 WHERE prod.url = elegido.url
   AND COALESCE(prod.sub_categoria, '') <> elegido.sub;
