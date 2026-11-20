-- V131__subcategoria_herramienta_manual.sql
--
-- Gives sub_categoria to 'Herramienta Manual' with the names an Argentine ferretería uses: Pinza y Alicate, Destornillador y Puntas,
-- Llave y Tubo, Martillo y Maza, Medición, Albañilería, Espátula y Rasqueta, Corte y Limado, Prensa y Morsa, Pistola de Silicona,
-- Engrampadora y Remachadora and Juego y Set. As in V128, V129 and V130 the LEADING NOUN decides, after the sale prefix (here "juego" is
-- skipped too): "Juego de destornilladores" is a Destornillador, "Set de herramientas 100 piezas" a Juego y Set. Every rule vetoes the
-- power-tool words (eléctrica, inalámbrica, batería, 18v, neumática, taladro...), so a power tool misfiled in this category keeps no sub,
-- as do a llave de paso, an adhesive tape or a wire brush. A name no rule claims keeps an empty sub.
--
-- Same rule as HerramientaSubcategoryResolver (Java), normalized exactly as in V128 / V129 / V130. V131HerramientaAgreementTest runs both
-- over the same names. One single-line text blob, rows 'cat|sub|leaders|any|veto' joined by ';' and words by ',' (no raw newline: Sonar
-- flags one inside a literal); the first row that fires wins: the name must START with a leader, contain one of 'any' when present and
-- none of 'veto'. Rows locked by hand (bloqueado_por) are not touched; active and inactive rows alike; only a row whose sub changes is
-- written. SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE.
SET CONSTRAINTS ALL IMMEDIATE;

WITH vocab AS (
  SELECT split_part(t.fila, '|', 2) AS sub, t.orden,
         string_to_array(split_part(t.fila, '|', 3), ',') AS lideres,
         string_to_array(NULLIF(split_part(t.fila, '|', 4), ''), ',') AS algunas,
         string_to_array(NULLIF(split_part(t.fila, '|', 5), ''), ',') AS vetos
    FROM unnest(string_to_array('he|Pinza y Alicate| pinza, alicate, tenaza, pelacable|| unas , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Destornillador y Puntas| destornillador, atornillador|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Destornillador y Puntas| punta , puntas | phillips, ph1, ph2, ph3, torx, atornillar, impact, hexagonal, pozidriv, destornillador| logica , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Llave y Tubo| llave , llaves || paso , luz , gas , termica , impacto , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Llave y Tubo| bocallave, tubo , tubos , dado , dados , crique , criques || dados de goma , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Martillo y Maza| martillo, maza, mazo , mazos || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Medición| nivel , niveles , cinta metrica, metro , escuadra, medidor, calibre, regla , reglas , plomada, goniometro, tester, multimetro, analizador|| electrica , electrico , electricas , electricos , neumatic, taladro, amoladora;he|Medición| pistola| medir , temperatura | electrica , electrico , electricas , electricos , neumatic, taladro, amoladora;he|Albañilería| llana, fratacho, cuchara, pala , palas , pico , ventosa, balde , baldes || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Espátula y Rasqueta| espatula, rasqueta|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Corte y Limado| serrucho, arco , sierra , tijera, cutter, hacha, formon, lima , limas , corta, cincel, machete, cuchilla, cuchillo, navaja, gubia, corte || fresa , rotativa , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Corte y Limado| hoja , hojas | sierra , cutter| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Corte y Limado| repuesto , repuestos | cutter| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Corte y Limado| cepillo , cepillos | carpintero| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Prensa y Morsa| prensa, morsa, morza, sargento, mordaza|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Pistola de Silicona| pistola| aplicadora, calafatera, silicona, cartucho| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Engrampadora y Remachadora| engrampadora, engrapadora, grampadora, remachadora|| electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Juego y Set| herramientas , maletin , gabinete , valija , estuche || electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora;he|Juego y Set| caja , cajas | herramientas , set , kit , piezas | inglete , electrica , electrico , electricas , electricos , inalambric, bateria, brushless , 18v, 20v, neumatic, taladro, amoladora', ';')) WITH ORDINALITY AS t(fila, orden)
),
nombres AS (
  SELECT h.url,
         ' ' || btrim(regexp_replace(translate(lower(h.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS texto
    FROM productos h
   WHERE h.bloqueado_por IS NULL
     AND h.categoria = 'Herramienta Manual'
),
leidos AS (
  SELECT n.url, n.texto,
         regexp_replace(n.texto, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|juego|piezas|pza|pzs|pz|completo|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS inicio
    FROM nombres n
),
elegidas AS (
  SELECT l.url, (array_agg(v.sub ORDER BY v.orden))[1] AS sub
    FROM leidos l
    JOIN vocab v
      ON EXISTS (SELECT 1 FROM unnest(v.lideres) w WHERE left(l.inicio, length(w)) = w)
     AND (v.algunas IS NULL OR EXISTS (SELECT 1 FROM unnest(v.algunas) w WHERE position(w IN l.texto) > 0))
     AND (v.vetos IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(v.vetos) w WHERE position(w IN l.texto) > 0))
   GROUP BY l.url
)
UPDATE productos p
   SET sub_categoria = COALESCE(e.sub, '')
  FROM leidos l
  LEFT JOIN elegidas e ON e.url = l.url
 WHERE p.url = l.url
   AND COALESCE(p.sub_categoria, '') <> COALESCE(e.sub, '');
