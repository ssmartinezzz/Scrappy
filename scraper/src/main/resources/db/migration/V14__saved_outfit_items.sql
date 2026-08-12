-- V14__saved_outfit_items.sql — normalize-db-schema-fks-1nf, corrección
--
-- `saved_outfits.slots_json`/`suplementos_json` NO eran documentos opacos del
-- cliente, como argumenté al dejarlos en jsonb en V10. Cada elemento es dato
-- del DOMINIO:
--
--   {"slot":"torso","url":"...","sitio":"...","nombre":"...","precio":15000,
--    "img":"...","categoria":"...","marca":"..."}
--
-- Ese `url` es `productos.url`, la misma clave que ya lleva FK en tres tablas
-- de este esquema. Y el resto son copias congeladas de la fila del producto.
-- "El backend no consulta adentro" describía el síntoma, no la causa: no
-- consultaba adentro PORQUE estaba en un blob. Dejar que la forma del payload
-- decida el esquema es tener la flecha al revés — la estructura de las tablas
-- condiciona al frontend, no al revés.
--
-- Lo que esto habilita y el blob no: preguntar qué outfits guardados contienen
-- un producto, cuál es la prenda más guardada, y —cuando `saved_outfits` gane
-- un `user_uuid`— relacionar al usuario con las prendas y no sólo con el
-- outfit.
--
-- SEMÁNTICA: FOTO + PRECIO ACTUAL (decisión del usuario). La fila guarda lo que
-- el producto ERA cuando se guardó (nombre, precio, img...), así que el outfit
-- sobrevive a que el producto cambie o desaparezca del catálogo; y conserva la
-- `url` para poder traer el precio de HOY por LEFT JOIN y mostrar "lo guardaste
-- a $15.000 · hoy $22.000".
--
-- POR ESO `url` NO LLEVA FK: es el mismo criterio que `agent_reclassify_audit`
-- en V4 — un registro histórico no puede depender de que el dato mutable siga
-- existiendo. Con FK, un producto discontinuado borraría (o bloquearía) un
-- outfit que el usuario guardó.
--
-- UNA SOLA TABLA para slots y suplementos: son la misma forma con distinto
-- discriminador, igual que `outfit_feedback_item` ya resuelve para el feedback.

CREATE TABLE saved_outfit_item (
    outfit_id BIGINT   NOT NULL REFERENCES saved_outfits(id) ON DELETE CASCADE,
    clase     TEXT     NOT NULL CHECK (clase IN ('slot', 'suplemento')),
    posicion  SMALLINT NOT NULL,
    ranura    TEXT     NOT NULL,
    url       TEXT     NOT NULL,
    sitio     TEXT,
    nombre    TEXT,
    precio    DOUBLE PRECISION,
    img       TEXT,
    categoria TEXT,
    marca     TEXT,
    PRIMARY KEY (outfit_id, clase, posicion)
);

-- >>> backfill:saved_outfit_item
INSERT INTO saved_outfit_item (outfit_id, clase, posicion, ranura, url, sitio, nombre, precio, img, categoria, marca)
SELECT o.id, 'slot', t.ord::smallint,
       coalesce(t.val->>'slot', ''), coalesce(t.val->>'url', ''),
       t.val->>'sitio', t.val->>'nombre',
       nullif(t.val->>'precio', '')::double precision,
       t.val->>'img', t.val->>'categoria', t.val->>'marca'
FROM saved_outfits o,
     LATERAL jsonb_array_elements(coalesce(o.slots_json, '[]'::jsonb)) WITH ORDINALITY AS t(val, ord)
WHERE coalesce(t.val->>'url', '') <> ''
UNION ALL
SELECT o.id, 'suplemento', s.ord::smallint,
       coalesce(s.val->>'tipo', ''), coalesce(s.val->>'url', ''),
       s.val->>'sitio', s.val->>'nombre',
       nullif(s.val->>'precio', '')::double precision,
       s.val->>'img', NULL, s.val->>'marca'
FROM saved_outfits o,
     LATERAL jsonb_array_elements(coalesce(o.suplementos_json, '[]'::jsonb)) WITH ORDINALITY AS s(val, ord)
WHERE coalesce(s.val->>'url', '') <> '';
-- <<< backfill:saved_outfit_item

ALTER TABLE saved_outfits DROP COLUMN slots_json;
ALTER TABLE saved_outfits DROP COLUMN suplementos_json;
