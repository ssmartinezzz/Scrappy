-- V144__categorias_bebe_salud_libreria.sql
--
-- Three categories and the sub-categories of five appliances, plus the column that lets a curated leaf carry a sub.
--
-- 'Artículo de Bebé' (juguetes), 'Salud' (belleza) and 'Librería' (oficina): strollers, cots, bottles and baby monitors; thermometers,
-- blood-pressure monitors, body scales and massagers; notebooks, binders, pencil cases and paper. All three deflate by IPC like their
-- rubro (no categoria.indice). A new category is TWO changes: the classifier produces it (BebeClassifier, SaludClassifier,
-- LibreriaClassifier) and this migration inserts the row that productos.categoria (fk_productos_categoria) accepts and the
-- categoria_rubro row that RubroResolver reads. Rows still sitting in 'Otros' move on the next scrape or renormalizar, not here.
--
-- Appliance subs, on the rows already stored (GrandesElectroSubcategoryResolver, the same text as V128 to V132): Lavarropas (Lavarropas,
-- Lavavajillas, Secarropas), Cocina y Horno (Cocina, Horno, Microondas, Anafe), Heladera (Heladera, Freezer, Cava, Exhibidora), Calefactor
-- (Caloventor, Convector, Estufa, Panel, Radiador, Tiro Balanceado, Sin Salida) and Termotanque (Eléctrico, A Gas). They are Title Case
-- because none of the five had a sub before (V143 stayed lowercase only because Reloj already had lowercase ones). One single-line text blob,
-- rows 'cat|sub|leaders|any|veto' joined by ';' and words by ',' (no raw newline: Sonar flags one inside a literal); the first row that
-- fires wins: the name must START with a leader (' ' leads every name), contain one of 'any' when present and none of 'veto'.
-- V144GrandesElectroAgreementTest runs this SQL and the Java over real names. Rows locked by hand (bloqueado_por) are not touched; active and
-- inactive rows alike; only a row whose sub changes is written.
--
-- sitio_categoria.sub_categoria (nullable): the curated sub of a leaf, read by NormalizerService only when the row ends in the leaf's own
-- category and the name gave no sub. NULL means none (no sentinel); a sub without a category is not a valid row (the CHECK).
--
-- Re-runnable: the column is added IF NOT EXISTS (so is its CHECK, as part of it), the categories and rows ON CONFLICT, the subs only when
-- they change. SET CONSTRAINTS flushes the deferred trigger of V40 before the DDL. The V40 trigger bumps catalog_version once at COMMIT.
-- One block, so the names, the blob and the accent list are written once (Sonar S1192).
SET CONSTRAINTS ALL IMMEDIATE;

DO $v144$
DECLARE
    bebe         CONSTANT text := 'Artículo de Bebé';
    salud        CONSTANT text := 'Salud';
    libreria     CONSTANT text := 'Librería';
    sin_sub      CONSTANT text := '';
    vocabulario  CONSTANT text := 'la|Secarropas| secarropas , secadora , centrifuga , centrifugo ;la|Lavavajillas| | lavavajillas , lavavajilla , lavaplatos ;la|Lavarropas| | lavarropas , lavarropa , lavasecarropas , lavasecarropa , lavaseca , lavasecas , lavadora , carga frontal , carga superior , semiautomatico , semiautomatica ;la|Secarropas| | secarropas , secadora , centrifuga , centrifugo ;co|Microondas| | microondas , microonda ;co|Anafe| anafe , anafes ;co|Cocina| cocina , cocinas ;co|Horno| | horno , hornos , hornito , hornitos ;co|Anafe| | anafe , anafes ;co|Cocina| | cocina , cocinas ;he|Exhibidora| | exhibidora , exhibidoras , exhibidor , visicooler ;he|Cava| | cava , cavas , vinoteca , vinotecas ;he|Heladera| | heladera , heladeras , refrigerador , refrigeradora , frigobar , side by side , nevera , combi ;he|Freezer| | freezer , freezers , congelador , congeladora ;ca|Tiro Balanceado| | tiro balanceado , tiraje balanceado , tbu , tb ;ca|Sin Salida| | sin salida , s salida , ss ;ca|Caloventor| | caloventor , caloventores , turbocalefactor , termoventilador ;ca|Convector| | convector , convectores ;ca|Panel| | panel , paneles ;ca|Radiador| | radiador , radiadores , oleoelectrico , oleo electrico ;ca|Estufa| | estufa , estufas , cuarzo , infrarroja , infrarrojo , halogena , halogeno , garrafera , exterior , patio ;te|A Gas| | gas , multigas , kcal , gn , ge , natural , envasado ;te|Eléctrico| | electrico , electrica , electr , electr. ';
BEGIN
    ALTER TABLE sitio_categoria ADD COLUMN IF NOT EXISTS sub_categoria text
        CONSTRAINT chk_sitio_categoria_sub_con_categoria
        CHECK (sub_categoria IS NULL OR (btrim(sub_categoria) <> '' AND categoria IS NOT NULL));

    INSERT INTO categoria (nombre, indice) VALUES (bebe, NULL), (salud, NULL), (libreria, NULL)
    ON CONFLICT (nombre) DO NOTHING;

    INSERT INTO categoria_rubro (categoria, sub_categoria, rubro)
    VALUES (bebe, sin_sub, 'juguetes'), (salud, sin_sub, 'belleza'), (libreria, sin_sub, 'oficina')
    ON CONFLICT (categoria, sub_categoria) DO UPDATE SET rubro = EXCLUDED.rubro;

    WITH claves (categoria, clave) AS (VALUES
      ('Lavarropas', 'la'), ('Cocina y Horno', 'co'), ('Heladera', 'he'), ('Calefactor', 'ca'), ('Termotanque', 'te')
    ), reglas AS (
      SELECT split_part(f.fila, '|', 1) AS clave, split_part(f.fila, '|', 2) AS etiqueta, f.orden,
             string_to_array(split_part(f.fila, '|', 3), ',') AS inicios,
             string_to_array(NULLIF(split_part(f.fila, '|', 4), sin_sub), ',') AS contiene,
             string_to_array(NULLIF(split_part(f.fila, '|', 5), sin_sub), ',') AS excluye
        FROM unnest(string_to_array(vocabulario, ';')) WITH ORDINALITY AS f(fila, orden)
    ), textos AS (
      SELECT b.url, b.clave, b.cuerpo,
             regexp_replace(b.cuerpo, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS arranque
        FROM (
          SELECT p.url, k.clave,
                 ' ' || btrim(regexp_replace(translate(lower(p.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS cuerpo
            FROM productos p
            JOIN claves k ON k.categoria = p.categoria
           WHERE p.bloqueado_por IS NULL
        ) b
    ), candidatas AS (
      SELECT t.url, r.etiqueta, row_number() OVER (PARTITION BY t.url ORDER BY r.orden) AS puesto
        FROM textos t
        JOIN reglas r
          ON r.clave = t.clave
         AND EXISTS (SELECT 1 FROM unnest(r.inicios) w WHERE left(t.arranque, length(w)) = w)
         AND (r.contiene IS NULL OR EXISTS (SELECT 1 FROM unnest(r.contiene) w WHERE position(w IN t.cuerpo) > 0))
         AND (r.excluye IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(r.excluye) w WHERE position(w IN t.cuerpo) > 0))
    )
    UPDATE productos AS pr
       SET sub_categoria = COALESCE(c.etiqueta, sin_sub)
      FROM textos t
      LEFT JOIN candidatas c ON c.url = t.url AND c.puesto = 1
     WHERE pr.url = t.url
       AND COALESCE(pr.sub_categoria, sin_sub) <> COALESCE(c.etiqueta, sin_sub);
END
$v144$;
