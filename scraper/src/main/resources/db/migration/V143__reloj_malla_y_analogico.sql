-- V143__reloj_malla_y_analogico.sql
--
-- Two new subs of 'Reloj': 'malla' (a strap, which stays a tecnologia accessory) and 'analógico' (a watch that says nothing smart:
-- Casio, Gadnic, Hadriatica's retro watches), which categoria_rubro sends to indumentaria. They are lowercase like 'smartwatch' and
-- 'deportivo', the two the category already had, so one category does not mix cases (the chip labels are in subcategorias.js).
-- Chargers and covers sold for a watch leave 'Reloj' for 'Celular'; that is the classifier's move (RelojReglas.esAccesorio) and renormalizar
-- applies it, as it applies every category move: this migration only writes the subs, which renormalizar would write too but the
-- categoria_rubro row needs the sub in place first.
--
-- Rows ('Reloj', active or inactive; a row locked by hand, bloqueado_por, is not touched):
--   a strap leads the name ("Malla Para Reloj ...", "Pack 3 Malla ...", "Correa de Titanio", "Pulsera ... para Apple Watch"): sub 'malla',
--            whatever it was ('smartwatch' came from the model name in it);
--   a plain watch (leads with "reloj" or has "casio", has one of casio / g shock / analogico / cuarzo / digital / gadnic / sumergible /
--            vintage / hombre / mujer / unisex, and none of the smart, sport, maker or non-watch words of NO_ES_COMUN) whose sub is blank:
--            'analógico'. A watch whose sub is 'smartwatch' or 'deportivo' keeps it: the keyword tier of SubcategoryResolver ran first.
--   rubro: a 'analógico' row on a site with no forced rubro is indumentaria (a forced site keeps its own, as RubroResolver does).
-- The regexes are the same text as RelojReglas over the same tokens (the name lower-cased, accent-folded, every run of non [a-z0-9] one
-- space, space-padded); V143RelojAgreementTest runs both over real names. Re-runnable: a second run finds nothing to change. The V40
-- trigger bumps catalog_version once at COMMIT however many rows change. No ALTER TABLE on productos, so the SET CONSTRAINTS flush of
-- V112 is not needed. One block, so the names and the regexes are written once (Sonar S1192).
DO $v143$
DECLARE
    reloj         CONSTANT text := 'Reloj';
    malla         CONSTANT text := 'malla';
    analogico     CONSTANT text := 'analógico';
    indumentaria  CONSTANT text := 'indumentaria';
    acentos       CONSTANT text := 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ';
    sin_acentos   CONSTANT text := 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN';
    prefijo       CONSTANT text := '(?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*';
    es_malla      CONSTANT text := '^ ' || prefijo || '(?:(?:malla|mallas|correa|correas|strap|straps) |pulseras? (?:[a-z0-9]+ )?para )';
    es_comun      CONSTANT text := '^(?=(?: ' || prefijo || '(?:reloj|relojes|rejoj) |.* casio ))'
                                   || '(?=.* (?:casio|g shock|gshock|analogico|analogica|cuarzo|digital|gadnic|sumergible|vintage|hombre|mujer|unisex) )';
    no_es_comun   CONSTANT text := ' (?:smart|smartwatch|inteligente|band|gps|deportivo|running|watch|garmin|forerunner|suunto|polar|fitbit'
                                   || '|amazfit|huawei|xiaomi|samsung|apple|galaxy|redmi|radio|despertador|comparador|cargador|carga|programador|horario'
                                   || '|reparacion|perfume|clock|localizador|proyector) ';
BEGIN
    INSERT INTO categoria_rubro (categoria, sub_categoria, rubro)
    VALUES (reloj, analogico, indumentaria)
    ON CONFLICT (categoria, sub_categoria) DO UPDATE SET rubro = EXCLUDED.rubro;

    WITH tok AS (
        SELECT p.url, p.sub_categoria,
               ' ' || btrim(regexp_replace(' ' || lower(translate(p.nombre, acentos, sin_acentos)) || ' ', '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
          FROM productos p
         WHERE p.categoria = reloj AND p.bloqueado_por IS NULL
    ), nueva AS (
        SELECT k.url,
               CASE WHEN k.t ~ es_malla THEN malla
                    WHEN k.sub_categoria = '' AND k.t ~ es_comun AND k.t !~ no_es_comun THEN analogico
                    ELSE k.sub_categoria END AS sub
          FROM tok k
    )
    UPDATE productos p
       SET sub_categoria = n.sub
      FROM nueva n
     WHERE p.url = n.url
       AND p.sub_categoria IS DISTINCT FROM n.sub;

    UPDATE productos p
       SET rubro = cr.rubro
      FROM categoria_rubro cr, sitio s
     WHERE cr.categoria = reloj
       AND cr.sub_categoria = analogico
       AND p.categoria = reloj
       AND p.sub_categoria = analogico
       AND s.sitio_key = p.sitio_key
       AND COALESCE(s.rubro_forzado, '') = ''
       AND p.rubro IS DISTINCT FROM cr.rubro
       AND p.bloqueado_por IS NULL;
END
$v143$;
