-- V103__tecnologia_subcategoria_plataforma.sql
--
-- productos.sub_categoria for hardware by platform (picks-tecnologia-subcategorias). Same rules as
-- SubcategoryResolver.hardware: /api/ml/renormalizar rewrites sub_categoria from the resolver, so any drift between the
-- two shows up as the sub flipping on the next renormalize. SubcategoryResolverTecnologiaTest pins the Java side.
--   CPU, Motherboard -> am4 | am5 | lga1700 | lga1851 | lga1200 | lga1151   (socket in the name; else the CPU model
--                       generation / the chipset)
--   RAM              -> notebook | ddr5 | ddr4 | ddr3   (DDR2 stays ''; no DDR token: the MHz / MT/s says it)
--   GPU              -> nvidia | amd | intel
--   Auricular        -> in-ear (buds/TWS) | inalámbrico | gamer | oficina | in-ear (generic words)
--   PC               -> gamer (dedicated GPU) | oficina
--   Gabinete         -> none
-- Names are tokenized (every run of non [a-z0-9] becomes one space) because the platform words arrive glued to
-- punctuation ("AM5,", "(am5)", "S.1700"). Never a row locked by hand (V3). Idempotent: IS DISTINCT FROM.
--
-- The same statement also moves rows the classifier put in the wrong categoria (TechClassifier: the leading noun beats
-- a GPU / headset keyword in the name), so a GPU-looking name that is really a water cooler never gets a GPU sub:
--   GPU       -> Cooler | Gabinete | Fuente | Mouse | Otros    ("Water Cooler ... Titan 240 RX", "Pasta Térmica ... Gpu")
--   Auricular -> Consola | Mousepad | Teclado | Mouse | Reloj | Otros    ("My Arcade ... Headphone Jack", combos, stands)
--   Joystick  -> Otros                                       (a stand "para Auriculares y Joystick")
--   CPU, GPU, RAM -> Combo de Actualización                  ("Combo Actualización ... Ram + SSD")
-- A moved row gets the sub the resolver gives its new categoria. The rollback in docs/DATABASE.md names the WHERE.
--
-- The rules are lookup tables, not CASE ladders, so each value (a categoria, a sub) is written once and a rule reads
-- as one row (Sonar plsql:S1192 flags a literal repeated three times, and the old ladder repeated 'gamer', 'am5',
-- 'Otros'... in every branch). First match wins, in `prioridad` order:
--   rasgo   named features of a name, computed once (a GPU word, a headset word, a mouse pad word...); velocidad adds
--           the DDR generation a RAM speed implies
--   mover   categoria -> categoria: one regex over  <categoria>:<name>:<rasgos>
--   subcat  sub_categoria: one regex over  <new categoria>:<name>:<rasgos>; the categoria is the regex's first term, so
--           one row holds every categoria a sub applies to. A NULL sub is a stop: the name is recognised and no
--           later rule may guess ("am3" is a socket outside the taxonomy, DDR2 a generation outside it).
-- Names are always ' ' + tokens + ' ', so a ' token ' regex never sees half a word.

WITH rasgo (clave, re) AS (
  VALUES ('gpu',       'gpu|tarjeta de video|video card|graphics card| rtx | gtx | rx [45679]|radeon|geforce'
                       '| arc (?:a3|a5|a7|b5|pro )|placa de video|placa video'),
         ('auricular', 'auricular|headset|headphone|earphone|earbud|inalambrico bt|over ear|in ear|on ear'),
         ('mousepad',  ' (?:mouse pad|mousepad|pad mouse|alfombrilla mouse) '),
         ('teclado',   ' (?:teclados?|keyboard) '),
         ('mouse',     ' (?:mouse|raton) (?:gamer|gaming|inalambrico|bluetooth|rgb) | gaming mouse '
                       '|^(?!.*(?:mickey|minnie|disney|mouse de )).* mouse '),
         ('reloj',     ' (?:reloj|relojes|smartwatch|smart watch|smartband|smart band) '),
         -- a name that starts like a peripheral / combo / stand, or mentions a headphone jack, and does not start by
         -- naming a headset: the Auricular rows that are something else
         ('entrada',   '^(?! (?:auricular|auriculares) )(?:.*headphones? jack| (?:combo|kit|mouse|teclado|soporte|reloj) )'),
         -- a dedicated GPU by maker (a bare "radeon" is the CPU's integrated graphics: not here)
         ('nvidia',    ' (geforce|quadro|rtx[a-z0-9]*|gtx[a-z0-9]*|gt[0-9]{3,4}[a-z0-9]*) | gt [0-9]{3,4}[a-z0-9]* '),
         ('amd',       ' rx[0-9]{3,4}[a-z0-9]* | rx [0-9]{3,4}[a-z0-9]* '),
         ('intel',     ' arc (pro )?[ab][0-9]{2,3}[a-z0-9]* ')
), velocidad (clave, desde, hasta) AS (
  -- no DDR token: the first "NNNN MHz / MT/s" in the name names the generation (the "mhz_*" rasgos)
  VALUES ('mhz_ddr5', 4800, 99999),
         ('mhz_ddr4', 2133, 3600),
         ('mhz_ddr3', 0,    1866)
), mover (prioridad, re, destino) AS (
  VALUES (1,  '^(?:CPU|GPU|RAM): combo (?:de )?actualizacion ',                         'Combo de Actualización'),
         -- a stand / rig / structure of a GPU or a headset
         (2,  '^(?:GPU|Auricular|Joystick): (?:soporte|soportes|rig|estructura) [^:]*:.*\m(?:gpu|auricular)\M', 'Otros'),
         (3,  '^GPU: (?:cooler|watercooler|water cooler|water cooling|watercooling|aio|pasta) ', 'Cooler'),
         (4,  '^GPU: gabinete ',                                                         'Gabinete'),
         (5,  '^GPU: fuente ',                                                           'Fuente'),
         (6,  '^GPU: mouse ',                                                            'Mouse'),
         (7,  '^Auricular: my arcade ',                                                  'Consola'),
         (8,  '^Auricular:[^:]*:(?=.*\mentrada\M)(?=.*\mmousepad\M)',                     'Mousepad'),
         (9,  '^Auricular:[^:]*:(?=.*\mentrada\M)(?=.*\mteclado\M)',                      'Teclado'),
         (10, '^Auricular:[^:]*:(?=.*\mentrada\M)(?=.*\mmouse\M)',                        'Mouse'),
         (11, '^Auricular:[^:]*:(?=.*\mentrada\M)(?=.*\mreloj\M)',                        'Reloj'),
         -- no GPU model at all, or an Auricular combo with nothing more specific
         (12, '^GPU:[^:]*:(?!.*\mgpu\M)|^Auricular:[^:]*:.*\mentrada\M',                 'Otros')
), subcat (prioridad, sub, re) AS (
  VALUES -- 1-7: an explicit socket token wins ("lga1700", "1700", "s1700")
         (1,  'am5',     '^(?:CPU|Motherboard):.* am5 '),
         (2,  'am4',     '^(?:CPU|Motherboard):.* am4 '),
         (3,  NULL,      '^(?:CPU|Motherboard):.* am3 '),
         (4,  'lga1851', '^(?:CPU|Motherboard):.* (?:lga|s)?1851 '),
         (5,  'lga1700', '^(?:CPU|Motherboard):.* (?:lga|s)?1700 '),
         (6,  'lga1200', '^(?:CPU|Motherboard):.* (?:lga|s)?1200 '),
         (7,  'lga1151', '^(?:CPU|Motherboard):.* (?:lga|s)?1151 '),
         -- 11-16: no token: the CPU model generation, or the motherboard chipset
         (11, 'am5',     '^CPU:.*ryzen [0-9]+ [7-9][0-9]{3}'
                         '|^Motherboard:.* (?:a620|b650|b840|b850|x670|x870)[a-z0-9]{0,2} '),
         (12, 'am4',     '^CPU:(?:.*ryzen [0-9]+ [3-6][0-9]{3}'
                         '|(?=.* athlon )(?=.* (?:3000g|200ge|220ge|240ge|300ge|320ge) ))'
                         '|^Motherboard:.* (?:a520|b450|b550|x570)[a-z0-9]{0,2} '),
         (13, 'lga1851', '^CPU:.*ultra [0-9]+ 2[0-9]{2}'
                         '|^Motherboard:.* (?:h810|b860|z890)[a-z0-9]{0,2} '),
         (14, 'lga1700', '^CPU:(?:.*i[3579] 1[234][0-9]{2}'
                         '|(?=.* (?:pentium|celeron) )(?=.* g74[0-9]{2}[a-z]* ))'
                         '|^Motherboard:.* (?:h610|b660|b760|z690|z790)[a-z0-9]{0,2} '),
         (15, 'lga1200', '^CPU:(?:.*i[3579] 1[01][0-9]{2}'
                         '|(?=.* (?:pentium|celeron) )(?=.* g(?:59|64)[0-9]{2}[a-z]* ))'
                         '|^Motherboard:.* (?:h410|b460|z490|h510|b560|z590)[a-z0-9]{0,2} '),
         (16, 'lga1151', '^CPU:(?:.*i[3579] [6-9][0-9]{3}'
                         '|(?=.* (?:pentium|celeron) )(?=.* g(?:4[0-9]{3}|5[0-4][0-9]{2})[a-z]* ))'
                         '|^Motherboard:.* (?:h310|b360|b365|z390|h370|h110|b150|h170|z170|b250|h270|z270)[a-z0-9]{0,2} '),
         -- 20-27 RAM: the DDR token, else the speed (DDR3 <= 1866, DDR4 2133-3600, DDR5 >= 4800); DDR2 stops the guess
         (20, 'notebook', '^RAM:.* (?:sodimm|so dimm|notebook|laptop) '),
         (21, 'ddr5',    '^RAM:.* (?:ddr5|dddr5) '),
         (22, 'ddr4',    '^RAM:.* (?:ddr4|dddr4) '),
         (23, 'ddr3',    '^RAM:.* ddr3 '),
         (24, NULL,      '^RAM:.* (?:ddr2|ddr) '),
         (25, 'ddr5',    '^RAM:[^:]*:.*\mmhz_ddr5\M'),
         (26, 'ddr4',    '^RAM:[^:]*:.*\mmhz_ddr4\M'),
         (27, 'ddr3',    '^RAM:[^:]*:.*\mmhz_ddr3\M'),
         -- 30-32 GPU: the maker, from the model (the rasgos) or from a word only a GPU row can use
         (30, 'nvidia',  '^GPU:(?:[^:]* (?:nvidia|[0-9]{3,4}gt) |[^:]*:.*\mnvidia\M)'),
         (31, 'amd',     '^GPU:(?:[^:]* (?:radeon|r[579] [0-9]{3}[a-z0-9]*) |[^:]*:.*\mamd\M)'),
         (32, 'intel',   '^GPU:[^:]*:.*\mintel\M'),
         -- 40-45 Auricular, Mouse, Teclado and PC: one row per sub, every categoria it applies to
         (40, 'in-ear',  '^Auricular:.* (?:earbuds?|buds|tws|true wireless) '),
         (41, 'mecánico', '^Teclado:.* (?:mecanico|mechanical) '),
         (42, 'inalámbrico',
                         '^(?:Mouse|Teclado):.* (?:inalambrico|wireless|bluetooth) '
                         '|^Auricular:.* (?:inalambric[a-z0-9]*|wireless|wireles|bluetooth|bluetooh) '),
         (43, 'gamer',   '^(?:Mouse|Teclado):.* (?:gamer|gaming) '
                         '|^Auricular:(?:.* (?:gamers?|gaming|quantum|hyperx|virtuoso|redragon|astro|razer|arctis|fantech'
                         '|cougar|aula|gxt|rog|tuf|afterglow|pdp|vsg|raptor|surround|rgb|gx|7 1|g pro|hs g[0-9]+[a-z]*) '
                         '|(?=.* corsair )(?=.* hs[0-9]+[a-z]* )'
                         '|(?=.* logitech )(?=.* g[0-9]{3}[a-z]* )'
                         '|(?=.* cooler master )(?=.* ch[0-9]+[a-z]* ))'
                         '|^PC:[^:]*:.*\m(?:nvidia|amd|intel)\M'),
         (44, 'oficina', '^Auricular:(?:.* zone |(?=.* logitech )(?=.* h[0-9]{3}[a-z]* )'
                         '|(?=.* genius )(?=.* hs [0-9]+[a-z]* ))'
                         '|^PC:'),
         (45, 'in-ear',  '^Auricular:.* (?:in ear|earphones?|intraural) '),
         -- 50-57 the categorias a row can be moved to
         (50, 'líquida', '^Cooler:.* (?:water|aio|liquida|watercooling) '),
         (51, 'gabinete', '^Cooler:.* (?:gabinete|case) '),
         (52, 'pasta',   '^Cooler:.* (?:grasa|pasta) '),
         (53, 'aire',    '^Cooler:.* (?:aire|torre|disipador) '),
         (54, 'modular', '^Fuente:.* modular '),
         (55, '80 plus', '^Fuente:.* (?:plus|bronze|gold|platinum) '),
         (56, 'smartwatch', '^Reloj:.* (?:smartwatch|smart|inteligente) '),
         (57, 'deportivo', '^Reloj:.* (?:deportivo|running|gps) ')
), base AS (
  -- One normalization, read once per name. A leading "outlet" is not the product noun: the classifier skips it too.
  SELECT url, categoria,
         regexp_replace(format(' %s ', btrim(regexp_replace(lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')),
                                                            '[^a-z0-9]+', ' ', 'g'))), '^ outlet ', ' ') AS n
    FROM productos
   WHERE rubro = 'tecnologia'
     AND bloqueado_por IS NULL
     AND categoria ~ '^(?:CPU|Motherboard|RAM|GPU|Auricular|PC|Joystick)$'
), con_rasgos AS (
  SELECT b.url, b.categoria, b.n,
         array_to_string(ARRAY(SELECT r.clave FROM rasgo r WHERE b.n ~ r.re)
                         || ARRAY(SELECT v.clave FROM velocidad v WHERE s.mhz BETWEEN v.desde AND v.hasta), ',') AS rasgos
    FROM base b
   CROSS JOIN LATERAL (SELECT substring(b.n from '([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ')::int AS mhz) s
), destino AS (
  -- The categoria TechClassifier gives the name today (the leading noun beats a GPU / headset keyword).
  SELECT o.url, o.n, o.rasgos, o.categoria AS origen,
         coalesce((SELECT m.destino FROM mover m
                    WHERE format('%s:%s:%s', o.categoria, o.n, o.rasgos) ~ m.re
                    ORDER BY m.prioridad LIMIT 1), o.categoria) AS categoria
    FROM con_rasgos o
), nuevo AS (
  SELECT d.url, d.categoria,
         coalesce((SELECT s.sub FROM subcat s
                    WHERE format('%s:%s:%s', d.categoria, d.n, d.rasgos) ~ s.re
                    ORDER BY s.prioridad LIMIT 1), '') AS sub
    FROM destino d
   WHERE d.categoria <> d.origen
      OR d.origen !~ '^Joystick$' -- a Joystick that did not move is not ours
)
UPDATE productos
   SET categoria = nuevo.categoria,
       sub_categoria = nuevo.sub
  FROM nuevo
 WHERE productos.url = nuevo.url
   AND (productos.sub_categoria IS DISTINCT FROM nuevo.sub
        OR productos.categoria IS DISTINCT FROM nuevo.categoria);

-- 2. A sport sub on a technology category is a tier-2 leak ("Mario Tennis" was a 'tenis' Videojuego). The resolver no
-- longer produces it; renormalizar would clear it, this clears the stored rows. The list is SubcategoryResolver's
-- TECNOLOGIA_SIN_DEPORTE; Reloj is not in it.
UPDATE productos
   SET sub_categoria = ''
 WHERE rubro = 'tecnologia'
   AND bloqueado_por IS NULL
   AND sub_categoria IN ('natación', 'hockey', 'fútbol', 'pádel', 'tenis', 'running', 'vóley', 'básquet',
                         'ciclismo', 'escalada', 'snowboarding')
   AND categoria ~ ('^(?:Gabinete|Almacenamiento|Red|Cable|Impresión|Cooler|Fuente|Teclado|Mouse|Monitor|Notebook'
                    '|Joystick|Cámara|Videojuego|Mousepad|Combo de Actualización|Silla|TV y Audio|Micrófono|Tablet'
                    '|Webcam|Consola|Mini PC|UPS|Soporte Monitor|Escritorio)$');
