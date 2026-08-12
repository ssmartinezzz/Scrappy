-- V10__saved_outfits_jsonb.sql — normalize-db-schema-fks-1nf, cierre
--
-- `saved_outfits.slots_json` y `suplementos_json` NO se normalizan, y la
-- decisión es deliberada: el backend los serializa desde el cuerpo del request
-- y los devuelve verbatim, sin consultar jamás adentro. Son documentos del
-- cliente, no grupos repetitivos que el dominio entienda. Inventarles un
-- esquema relacional sería acoplar la base a una forma que sólo el frontend
-- conoce y que puede cambiar sin que la base tenga nada que decir.
--
-- Lo que sí se puede exigir sin inventar nada: que sean JSON válido. Como TEXT,
-- un string roto se guardaba feliz y explotaba recién al leerlo. Como jsonb, la
-- base lo rechaza en el INSERT, que es donde se puede hacer algo al respecto.
--
-- `suplementos_json` es NULLable a propósito (un outfit sin suplementos), así
-- que el USING sólo castea lo no nulo.

ALTER TABLE saved_outfits
    ALTER COLUMN slots_json TYPE jsonb USING slots_json::jsonb;

ALTER TABLE saved_outfits
    ALTER COLUMN suplementos_json TYPE jsonb USING suplementos_json::jsonb;
