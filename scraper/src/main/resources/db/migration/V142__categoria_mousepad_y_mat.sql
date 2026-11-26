-- V142__categoria_mousepad_y_mat.sql
--
-- Category 'Mousepad y Mat' replaces 'Mousepad' (tecnologia) and 'Mat Escritorio' (oficina): a pad and a desk mat are one shelf. Its rubro
-- is oficina on every site, and 'categoria_rubro.pisa_forzado' (V138) makes that win over a store forced to tecnologia. The price keeps
-- following the dollar through categoria.indice, as it did under tecnologia. 'Escritorio' gets a second row, sub 'Motorizado', with
-- the same flag: a desk with a proven motor is oficina in a technology store too.
-- A new category is TWO changes: the classifier produces it, and this row lets productos.categoria (fk_productos_categoria) accept it.
-- The references move in FK order: the new categoria and categoria_rubro rows first, then productos, sitio_categoria, categoria_stats,
-- categoria_dismiss and saved_outfit_item (the last two have no FK), then the old rows are deleted.
--
-- Sub-category (SubcategoryResolver -> MousepadReglas.SUBS): the first rule that matches wins, '' when none does. The regexes are the same
-- text as MousepadReglas over the same tokens (the name lower-cased, accent-folded, every run of non [a-z0-9] one space, space-padded).
-- Escritorio takes 'Motorizado' only when the name says motor, motorizado or electrico and does not say sin motor or manual
-- (EscritorioReglas); "regulable" alone is height-adjustable, not a motor. V142MousepadYMatAgreementTest runs this SQL and the Java over real names.
--
-- Rows:
--   'Mousepad', 'Mat Escritorio'  every one becomes 'Mousepad y Mat' (a rename), active or inactive, with its sub and rubro oficina. A row
--            locked by hand (bloqueado_por) is renamed too, because the old row cannot stay referenced, and keeps its sub.
--   'Escritorio'  a row with a proven motor takes sub 'Motorizado' and rubro oficina. A row locked by hand is not touched.
-- A kit of a keyboard and a mouse that lists a pad was a Mousepad and is renamed with the rest; so are wrist rests and desk mats that sit
-- in other categories (Teclado, Otros, Videojuego): the classifier moves all of them on the next scrape or renormalizar, not this migration.
-- Re-runnable: a second run finds no old category and no new Motorizado. The V40 trigger bumps catalog_version once at COMMIT however many rows
-- change. No ALTER TABLE on productos, so the SET CONSTRAINTS flush of V112 is not needed. One block, so the names, the regexes and the
-- accent list are written once (Sonar S1192).
DO $v142$
DECLARE
    nuevo        CONSTANT text := 'Mousepad y Mat';
    pad          CONSTANT text := 'Mousepad';
    mat          CONSTANT text := 'Mat Escritorio';
    viejas       CONSTANT text[] := ARRAY[pad, mat];
    escritorio   CONSTANT text := 'Escritorio';
    motorizado   CONSTANT text := 'Motorizado';
    oficina      CONSTANT text := 'oficina';
    acentos      CONSTANT text := 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ';
    sin_acentos  CONSTANT text := 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN';
    con_motor    CONSTANT text := ' (?:motor|motores|motorizad[oa]s?|electric[oa]s?) ';
    sin_motor    CONSTANT text := ' (?:sin motor|manual(?:es)?) ';
BEGIN
    INSERT INTO categoria (nombre, indice)
    SELECT nuevo, r.indice FROM rubro r WHERE r.clave = 'tecnologia'
    ON CONFLICT (nombre) DO NOTHING;

    INSERT INTO categoria_rubro (categoria, sub_categoria, rubro, pisa_forzado)
    VALUES (nuevo, '', oficina, true), (escritorio, motorizado, oficina, true)
    ON CONFLICT (categoria, sub_categoria) DO UPDATE SET rubro = EXCLUDED.rubro, pisa_forzado = EXCLUDED.pisa_forzado;

    WITH rx (ord, sub, re) AS (VALUES
      (1, 'Antifatiga', ' (?:antifatiga|anti fatiga) '),
      (2, 'Apoyamuñecas', ' (?:apoya ?munecas?|reposa ?munecas?|repose munecas?|descansa ?munecas?|wrist (?:rest|pad)) '),
      (3, 'Mousepad Ergonómico', ' ergonomic[oa]s? '),
      (4, 'Mat de Escritorio', ' (?:mat (?:de|para) escritorio|desk ?mat|desk pad|deskpad|alfombrilla (?:de|para) escritorio|mat board) '),
      (5, 'Mousepad RGB', ' (?:rgb|chroma|led|iluminad[oa]s?) '),
      (6, 'Mousepad XL', ' (?:xl|xxl|xxxl|[0-9]xl|extended|extendido|extra grande|gigante) '),
      (7, pad, ' (?:mouse ?pad|mousepad|pad mouse|alfombrilla (?:para )?mouse|mouse mat|pad) ')
    ), tok AS (
      SELECT p.url,
             ' ' || btrim(regexp_replace(' ' || lower(translate(p.nombre, acentos, sin_acentos)) || ' ', '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
        FROM productos p
       WHERE p.categoria = ANY(viejas)
    )
    UPDATE productos p
       SET categoria = nuevo,
           sub_categoria = CASE WHEN p.bloqueado_por IS NULL
                                THEN COALESCE((SELECT r.sub FROM rx r WHERE k.t ~ r.re ORDER BY r.ord LIMIT 1), '')
                                ELSE p.sub_categoria END,
           rubro = oficina
      FROM tok k
     WHERE p.url = k.url;

    UPDATE productos p
       SET sub_categoria = motorizado, rubro = oficina
      FROM (SELECT q.url,
                   ' ' || btrim(regexp_replace(' ' || lower(translate(q.nombre, acentos, sin_acentos)) || ' ', '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
              FROM productos q
             WHERE q.categoria = escritorio AND q.bloqueado_por IS NULL) k
     WHERE p.url = k.url
       AND k.t ~ con_motor AND k.t !~ sin_motor
       AND (p.sub_categoria IS DISTINCT FROM motorizado OR p.rubro IS DISTINCT FROM oficina);

    UPDATE sitio_categoria SET categoria = nuevo WHERE categoria = ANY(viejas);

    DELETE FROM categoria_stats s
     USING categoria_stats n
     WHERE s.categoria = ANY(viejas) AND n.categoria = nuevo;
    DELETE FROM categoria_stats s
     USING categoria_stats n
     WHERE s.categoria = mat AND n.categoria = pad;
    UPDATE categoria_stats SET categoria = nuevo WHERE categoria = ANY(viejas);

    UPDATE categoria_dismiss SET categoria = nuevo WHERE categoria = ANY(viejas);
    UPDATE saved_outfit_item SET categoria = nuevo WHERE categoria = ANY(viejas);

    DELETE FROM categoria_rubro WHERE categoria = ANY(viejas);
    DELETE FROM categoria WHERE nombre = ANY(viejas);
END
$v142$;
