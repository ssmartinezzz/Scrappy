-- V81__infantil_senales_de_nombre.sql
--
-- GenderResolver now also reads kids' signals that carry no size label: juvenil/juveniles, escolar/escolares,
-- the kids brand Footy and a narrow list of characters that only ever sell to kids (minnie, mickey,
-- paw patrol, peppa, frozen, toy story). Footy zapatillas, Crocs Frozen and Disney backpacks kept a blank
-- or adult genero and filled the adult /picks (dev, 2026-11-10). This aligns the rows already stored
-- instead of waiting for every site to be scraped again. The next scrape is the source of truth.
-- Rule (identical to GenderResolver): accent-stripped name, punctuation as space, whole words.
-- Footy holds in any rubro. The rest only in clothing (NULL/blank rubro = indumentaria): "escolar" is also
-- stationery and "Peppa Pig" is also a pet toy. Deliberately left out: Marvel, Spider-Man, Disney, Hello
-- Kitty, Stitch, Barbie (adult collabs and collectibles). Active or not, never a row locked by hand (V3).
-- Idempotent: a handled row is already 'infantil' and no longer matches its WHERE.
UPDATE productos
   SET genero = 'infantil'
 WHERE bloqueado_por IS NULL
   AND genero IS DISTINCT FROM 'infantil'
   AND (
        (' ' || regexp_replace(translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun'), '[^a-z0-9]+', ' ', 'g') || ' ')
            ~ ' footy '
        OR (COALESCE(NULLIF(rubro, ''), 'indumentaria') = 'indumentaria'
            AND (' ' || regexp_replace(translate(lower(btrim(nombre)), 'áéíóúüñ', 'aeiouun'), '[^a-z0-9]+', ' ', 'g') || ' ')
                ~ ' (juvenil|juveniles|escolar|escolares|minnie|mickey|paw patrol|peppa|frozen|toy story) ')
       );
