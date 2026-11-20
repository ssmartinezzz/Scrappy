-- V128__subcategoria_electro_tv_audio.sql
--
-- Gives sub_categoria to the two biggest categories that had none: 'Pequeño Electrodoméstico' (what the appliance is: Cafetera,
-- Licuadora y Exprimidor, Planchita y Rizador...) and 'TV y Audio' (Televisor, Parlante, Barra de Sonido, Soporte...). The LEADING
-- NOUN decides, as in the classifier: "Soporte para Smart TV 65" is a Soporte, "Accesorio para cafetera" is an Accesorio y Repuesto
-- and never a Cafetera. A name no rule claims keeps an empty sub (no catch-all).
--
-- It is the same rule as ElectroSubcategoryResolver (Java) over ' ' || name || ' ' with the accents of AccentStripper removed, the
-- punctuation / : , ; + ( ) [ ] turned into spaces (so "Parlante/Bluetooth" leads with "parlante") and the blanks collapsed;
-- the sale prefix of TextoClasificable.sinPrefijoDeVenta plus "set", "kit", "mini" and "philips" are skipped before the leader is read.
-- V128ElectroTvAudioAgreementTest runs both over the same names.
--
-- One single-line text blob (rows 'cat|sub|leaders|any|veto' joined by ';', the words by ','; no raw newline: Sonar flags one inside
-- a literal; rows are tried in order and the first that fires wins), not a literal per row:
--   leaders  the name (after the sale prefix) must START with one of them;
--   any      when present, the name must also contain one of them;
--   veto     when present, the name must contain none of them.
-- Rows locked by hand (bloqueado_por) are not touched, as in V103. Active and inactive rows alike. Only a row whose sub changes is written.
-- SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE (productos is altered in the same transaction).
SET CONSTRAINTS ALL IMMEDIATE;

WITH regla AS (
  SELECT split_part(t.r, '|', 1) AS cat, split_part(t.r, '|', 2) AS sub,
         string_to_array(split_part(t.r, '|', 3), ',') AS lideres,
         string_to_array(NULLIF(split_part(t.r, '|', 4), ''), ',') AS alguna,
         string_to_array(NULLIF(split_part(t.r, '|', 5), ''), ',') AS veto,
         t.ord
    FROM unnest(string_to_array('pe|Accesorio y Repuesto| accesorio , accesorios , repuesto , repuestos , filtro , filtros , tapa , cuchilla , cuchillas , boquilla , deposito , anaquel , estante , cajon , embudo , cilindro , guia , funda , clip , recambio , consumibles , juego de trapos , jarra de vidrio ;pe|Secador de Pelo| secador , secadores , cepillo secador , cepillo de aire || ropa , manos , unas ;pe|Planchita y Rizador| planchita , planchitas , alisador , alisadora , alizador , alizadora , rizador , rizadora , buclera , moldeador , moldeadora , multistyler , varita , cepillo alisador , cepillo modelador , cepillo termico , cepillo multistyler , cepillo rotativo , cepillo ionico , plancha de pelo , plancha de cabello , plancha alisadora , plancha alizadora , plancha y rizadora ;pe|Planchita y Rizador| plancha , plancha profesional | ga.ma , gama , babyliss , remington , titanium , ionic , ceramic , pulgada , pulgadas , professional , profesional , pelo , cabello , onduladora , secadora | vapor , seca , seco , vertical , asar , ropa ;pe|Plancha de Ropa| plancha , estacion de planchado , centro de planchado | vapor , vaporizadora , seca , seco , vertical , ropa , planchado , central | pelo , cabello , asar ;pe|Cafetera| cafetera , cafeteras , maquina de cafe , maquina para cafe , maquina de espresso , maquina espresso , maquina de expreso , maquina expreso , capsulera , bialetti , nespresso , dolce gusto ;pe|Licuadora y Exprimidor| licuadora , licuadoras , juguera , exprimidor , exprimidora , exprimidores , blender , extractor de jugo , extractor de jugos ;pe|Licuadora y Exprimidor| ninja | blender ;pe|Batidora y Mixer| batidora , batidoras , mixer , minipimer , batidor , amasadora ;pe|Procesadora| procesadora , procesadoras , procesador , picadora , multiprocesadora , robot de cocina , robot ;pe|Pava Eléctrica| pava , pavas , hervidora , jarra electrica , jarra pava , tetera electrica , kettle ;pe|Tostadora y Sandwichera| tostadora , tostadoras , tostador , sandwichera , sandwicheras , sanwichera , waflera , waflero , panini , paninera , grill ;pe|Freidora| freidora , freidoras , air fryer , airfryer ;pe|Freidora| horno | airfryer , air fryer , freidora ;pe|Afeitadora y Cortadora| afeitadora , afeitadoras , rasuradora , maquina de afeitar , maquina para afeitar , maquinas para afeitar , maquinas de afeitar , maquinita de pelo , cortapelo , cortapelos , cortacabello , cortabarba , recortadora , cortacabellos , corta barba , corta cabello , corta cabellos , corta pelo , corte de pelo , trimmer , clipper , patillera , multigroom , oneblade , barberia ;pe|Afeitadora y Cortadora| peluqueria , peluqueria barberia | cortadora , cortar pelo , patillera , clipper | tijera , tijeras , planchita , secador ;pe|Afeitadora y Cortadora| cortadora , maquina de cortar , maquina para cortar , maquina cortar , maquina cortadora , maquina corta || fiambre , fiambres , alimentos , papas , verduras , queso , cesped , pasto ;pe|Depiladora| depiladora , depiladoras , maquina depiladora , epiladora , depilador ;tv|Soporte| soporte , soportes , bandeja para , tripode , tripodes , brazo , pie , rackera , estante , aro ;tv|Barra de Sonido| barra de sonido , barra sonido , sound bar , soundbar , barra ;tv|Televisor| smart , smartv , tv , televisor , televisores , led , 24", 32", 40", 42", 43", 50", 55", 58", 60", 65", 70", 75", 77", 82", 85", 86", 98", 100" || box ;tv|Proyector| proyector , proyectores ;tv|Handy| handy , handys , handies , walkie ;tv|Consola y Mixer| consola , consolas , mixer , controlador , controladora , bandeja de dj , interface , interfaz , ecualizador ;tv|Tocadiscos| tocadiscos , giradiscos , giradisco , bandeja , bendeja , bamdeja , tornamesa ;tv|Radio| radio , radios , radiograbador , autoestereo , auto estereo ;tv|Equipo de Música| minicomponente , microcomponente , sistema audio , sistema de audio , equipo de musica , home theater ;tv|Parlante| parlante , parlantes , parlanteportatil , parl.port , parl.portatil , bafle , bafles , altavoz , altavoces , bocina , bocinas , speaker , speakers , subwoofer , torre , tower , sistema portatil , sistema portable , sistema de sonido , monitores , par de monitores , jbl , beats ', ';')) WITH ORDINALITY AS t(r, ord)
),
base AS (
  SELECT p.url,
         CASE p.categoria WHEN 'Pequeño Electrodoméstico' THEN 'pe' ELSE 'tv' END AS cat,
         ' ' || btrim(regexp_replace(translate(lower(p.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS n
    FROM productos p
   WHERE p.bloqueado_por IS NULL
     AND p.categoria IN ('Pequeño Electrodoméstico', 'TV y Audio')
),
venta AS (
  SELECT b.*, regexp_replace(b.n, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|philips|ga[.]ma|gama|italy|professional|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS sin
    FROM base b
),
nuevo AS (
  SELECT v.url,
         COALESCE((SELECT g.sub FROM regla g
                    WHERE g.cat = v.cat
                      AND EXISTS (SELECT 1 FROM unnest(g.lideres) l WHERE left(v.sin, length(l)) = l)
                      AND (g.alguna IS NULL OR EXISTS (SELECT 1 FROM unnest(g.alguna) a WHERE position(a IN v.n) > 0))
                      AND (g.veto IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(g.veto) x WHERE position(x IN v.n) > 0))
                    ORDER BY g.ord LIMIT 1), '') AS sub
    FROM venta v
)
UPDATE productos p
   SET sub_categoria = nuevo.sub
  FROM nuevo
 WHERE p.url = nuevo.url
   AND COALESCE(p.sub_categoria, '') <> nuevo.sub;
