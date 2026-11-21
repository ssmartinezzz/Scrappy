-- V133__zapatilla_por_modelo.sql
--
-- Moves 'Zapatilla' rows to the sibling shoe category their MODEL names, the way CalzadoPorModeloClassifier does since the
-- KW_*_ZAPATILLA lists: Zapatilla Skate, Zapatilla Running, Zapatilla Entrenamiento or Zapatilla Urbana. renormalizar feeds the stored
-- categoria back as raw and the classifier only reads the name, so a keyword change alone never moves a stored row: this does.
--
-- Same rule as the Java side. The name must carry a shoe word (zapatilla, sneaker, calzado, shoe(s), tenis, footwear): "Puma Caven III"
-- with no shoe word stays Zapatilla, in Java and here. A court / trail sport word (tenis, padel, basquet, trekking, golfexcept
-- "handball spezial"...) keeps the row a plain Zapatilla. The first rule that fires wins, in the order skate, running, training, urbana;
-- 'entrenamiento' (KW_TRAINING_GENERICO) goes first and is not subject to the sport veto, as in Java. Names are lower-cased, accent-folded
-- and space-padded like TextoClasificable.normalizado; words are matched as plain substrings, so the padding IS the word boundary.
-- One single-line text blob, rows 'destino|veta|palabras' joined by ';' and words by ',' (no raw newline: Sonar flags one inside a
-- literal). sub_categoria is kept as is. Rows locked by hand (bloqueado_por) are not touched; active and inactive rows alike. Idempotent:
-- a moved row no longer has categoria = 'Zapatilla'. SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE.
SET CONSTRAINTS ALL IMMEDIATE;

WITH reglas AS (
  SELECT split_part(f.fila, '|', 1) AS destino, split_part(f.fila, '|', 2) = '1' AS veta,
         string_to_array(split_part(f.fila, '|', 3), ',') AS palabras, f.orden
    FROM unnest(string_to_array('Zapatilla Entrenamiento|0|entrenamiento;Zapatilla Skate|1| dc shoes , vans , manteca , court graffik , pensford , knu skool , sk8 , old skool ;Zapatilla Running|1| skyrocket , flyer lite , adistar , strong pace , runfalcon , revolution , run defy , cloudrunner , cloudmonster , cloudsurfer , cloudflyer , cloudswift , lightblaze , galaxy , response , downshifter , quest , journey run , ultrarun , aero glide , ultra flow , alphaglide , supernova , nitro , go run , max cushioning , wind 5 , wind iv , tensaur run , winflo , gel-, novablast ;Zapatilla Entrenamiento|1| scend pro , squat , dropset , mc trainer , flex control , disperse ;Zapatilla Urbana|1| grand court , caven , suede , carina , rebound , court vision , vl court , court classic , karmen , ca pro , club c , segovia , acd classic , handball spezial , renno , break start , advantage , breaknet , run 70s , technochaos , killshot , initiator , storm 96 , club ii , shuffle , court lally , r78 , rickie , ca match , bb 4000 , disruptor , court 80 , court 90 , uproot , nova low , john foos , skechers uno , bobs , smash , mayze , palermo , speedcat , court royale , park lifestyle , hoops , lacoste ', ';')) WITH ORDINALITY AS f(fila, orden)
),
textos AS (
  SELECT p.url,
         ' ' || lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')) || ' ' AS n
    FROM productos p
   WHERE p.categoria = 'Zapatilla'
     AND p.bloqueado_por IS NULL
),
aptas AS (
  SELECT t.url, t.n,
         EXISTS (SELECT 1 FROM unnest(string_to_array(' tenis , tennis , padel , basquet , basket , voley , volley , hockey , futsal , trekking , hiking , outdoor , terrex , golf , ciclismo ', ',')) w WHERE position(w IN t.n) > 0)
           OR (position(' handball ' IN t.n) > 0 AND position(' handball spezial ' IN t.n) = 0) AS deporte
    FROM textos t
   WHERE EXISTS (SELECT 1 FROM unnest(string_to_array('zapatilla,sneaker,calzado, shoe , shoes ,tenis,footwear', ',')) w WHERE position(w IN t.n) > 0)
),
elegidas AS (
  SELECT DISTINCT ON (a.url) a.url, r.destino
    FROM aptas a
    JOIN reglas r
      ON (NOT r.veta OR NOT a.deporte)
     AND EXISTS (SELECT 1 FROM unnest(r.palabras) w WHERE position(w IN a.n) > 0)
   ORDER BY a.url, r.orden
)
UPDATE productos AS pr
   SET categoria = e.destino
  FROM elegidas e
 WHERE pr.url = e.url;
