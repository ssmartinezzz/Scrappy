-- V127__categoria_accesorio_apple.sql
--
-- New category 'Accesorio Apple': what is sold for an iPad, a Mac, AirPods, an AirTag or an Apple Watch (cases, straps, screen
-- protectors, stands, the Pencil), split by sub_categoria iPad / MacBook / AirPods / Apple Watch (the device the name mentions
-- first; AirTag items have no sub). Rubro tecnologia (CategoryGroups.CATEGORIAS_TECH). An accessory that also names an iPhone is
-- not here: it stays a Celular accessory. A generic tablet or notebook accessory that names no Apple device stays where it is.
--
-- A new category is TWO changes: EcosistemaAppleClassifier produces it (the sub comes from SubcategoryResolver), and this row lets
-- productos.categoria (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a product violates the FK,
-- ProductRepository swallows the error and the symptom is "0 nuevos". CategoriaLookupTableTest requires this table and
-- CategoryGroups.canonicalCategories() to be the same set.
--
-- The statement after the row moves the stored rows that rule now claims, as V103 and V118 do, so the catalogue does not wait for
-- a renormalize. It is the same rule as EcosistemaAppleClassifier over ' ' || name || ' ' with the accents of AccentStripper removed and
-- the punctuation kept ("position(' ipad ' in n)" is the Java contains): not a device model leader, not a trackpad, none of the
-- veto words, and either an Apple Pencil / AirTag leader, a watch strap (correa/banda + Apple Watch or "caja de NN mm"), or
-- an accessory noun together with an Apple device word. "Leader" ignores the sale prefix like TextoClasificable.sinPrefijoDeVenta.
-- One single-line text blob (clave|palabras rows joined by ';', the words by ','; no raw newline: Sonar flags one inside a literal),
-- not a literal per row. A row locked by hand (bloqueado_por) is not touched, as in V103. Rows of a keychain, organizer or toy
-- category are not candidates: their own leading-noun rules run before this one in the classifier. Active and inactive rows alike.
-- Rubro: the rubro RubroResolver gives a tech category (a site with a forced rubro keeps it, any other is tecnologia).
-- SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE (productos is altered in the same transaction).
SET CONSTRAINTS ALL IMMEDIATE;

INSERT INTO categoria (nombre) VALUES ('Accesorio Apple')
ON CONFLICT (nombre) DO NOTHING;

WITH k AS (SELECT 'Accesorio Apple'::text AS cat, ' caja de [0-9]{2} ?mm'::text AS caja_re),
linea(l) AS (SELECT regexp_split_to_table('dispositivo| mac mini , mac studio , imac , mac pro , studio display , pro display xdr , ipad , airpods , earpods , apple airpods , apple earpods , apple watch , apple tv , beats pill , jbl clip , jbl go , jbl flip , jbl charge , jbl boombox , beats solo , beats studio , beats flex , beats fit , powerbeats ;trackpad| trackpad ;lider| apple pencil , airtag ;correa| correa , banda ;accesorio| funda , fundas , case , estuche , smart folio , protector , protectores , film , filtro , lapiz , stylus , bumper , forro , soporte , base , cover , folio , vidrio , templado , todo terreno , dux , balance , puntas ;ref| ipad , macbook , imac , para mac , airpods , apple watch , airtag , apple pencil ;veto| teclado , keyboard , mouse , celular , notebook , laptop , iphone , organizadora , organizador ;sub:iPad| ipad , apple pencil ;sub:MacBook| macbook , imac , para mac ;sub:AirPods| airpods , earpods ;sub:Apple Watch| apple watch ', ';')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea),
voc AS (SELECT f[1] AS clave, unnest(string_to_array(f[2], ',')) AS palabra FROM campo),
base AS (
  SELECT p.url, COALESCE(s.rubro_forzado, '') AS forzado,
         ' ' || translate(lower(p.nombre), 'áàäéèëíìïóòöúùüñ', 'aaaeeeiiiooouuun') || ' ' AS n
    FROM productos p
    LEFT JOIN sitio s ON s.sitio_key = p.sitio_key
   WHERE p.bloqueado_por IS NULL
     AND COALESCE(p.categoria, '') NOT IN ('Llavero', 'Organización', 'Juguete', 'LEGO', 'Figura Coleccionable')
),
venta AS (
  SELECT b.*, regexp_replace(b.n, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS sin
    FROM base b
),
rasgo AS (
  SELECT v.url, v.forzado, v.n, k.caja_re,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'dispositivo' AND left(v.sin, length(w.palabra)) = w.palabra) AS dispositivo,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'trackpad' AND position(w.palabra IN v.n) > 0) AS trackpad,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'lider' AND left(v.sin, length(w.palabra)) = w.palabra) AS lider,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'correa' AND left(v.sin, length(w.palabra)) = w.palabra) AS correa,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'accesorio' AND position(w.palabra IN v.n) > 0) AS accesorio,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'ref' AND position(w.palabra IN v.n) > 0) AS ref,
         EXISTS (SELECT 1 FROM voc w WHERE w.clave = 'veto' AND position(w.palabra IN v.n) > 0) AS veto,
         position(' apple watch ' IN v.n) > 0 AS reloj,
         substring(v.n FROM k.caja_re) IS NOT NULL AS caja
    FROM venta v CROSS JOIN k
),
accesorio_apple AS (
  SELECT r.url, r.forzado, r.n, r.caja_re
    FROM rasgo r
   WHERE NOT r.dispositivo AND NOT r.trackpad AND NOT r.veto
     AND (r.lider OR (r.correa AND (r.reloj OR r.caja)) OR (r.accesorio AND (r.ref OR r.caja)))
),
nuevo AS (
  SELECT a.url, a.forzado,
         COALESCE((SELECT c.sub FROM (
                     SELECT substr(w.clave, 5) AS sub, position(w.palabra IN a.n) AS pos
                       FROM voc w WHERE w.clave LIKE 'sub:%' AND position(w.palabra IN a.n) > 0
                     UNION ALL
                     SELECT 'Apple Watch', position(substring(a.n FROM a.caja_re) IN a.n)
                      WHERE substring(a.n FROM a.caja_re) IS NOT NULL) c
                    ORDER BY c.pos LIMIT 1), '') AS sub
    FROM accesorio_apple a
)
UPDATE productos p
   SET categoria = k.cat,
       sub_categoria = nuevo.sub,
       rubro = CASE WHEN nuevo.forzado <> '' THEN nuevo.forzado ELSE 'tecnologia' END
  FROM nuevo CROSS JOIN k
 WHERE p.url = nuevo.url
   AND (p.categoria, p.sub_categoria) IS DISTINCT FROM (k.cat, nuevo.sub);
