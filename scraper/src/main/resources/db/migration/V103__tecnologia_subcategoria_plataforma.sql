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

-- One normalization, one CASE per concern. The CTE reads each name once; the flags are shared by GPU and PC.
WITH base AS (
  SELECT url, categoria,
         -- a leading "outlet" is not the product noun: the classifier skips it too
         regexp_replace(' ' || btrim(regexp_replace(lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')),
                                                    '[^a-z0-9]+', ' ', 'g')) || ' ', '^ outlet ', ' ') AS n
    FROM productos
   WHERE rubro = 'tecnologia'
     AND bloqueado_por IS NULL
     AND categoria IN ('CPU', 'Motherboard', 'RAM', 'GPU', 'Auricular', 'PC', 'Joystick')
), plataforma AS (
  SELECT url, categoria, n,
         -- an explicit socket token wins ("lga1700", "1700", "s1700"); 'otro' is a socket outside the taxonomy
         -- (it still blocks the fallbacks)
         CASE WHEN n ~ ' am5 '                       THEN 'am5'
              WHEN n ~ ' am4 '                       THEN 'am4'
              WHEN n ~ ' am3 '                       THEN 'otro'
              WHEN n ~ ' (?:lga|s)?1851 '            THEN 'lga1851'
              WHEN n ~ ' (?:lga|s)?1700 '            THEN 'lga1700'
              WHEN n ~ ' (?:lga|s)?1200 '            THEN 'lga1200'
              WHEN n ~ ' (?:lga|s)?1151 '            THEN 'lga1151'
              ELSE '' END AS socket_explicito,
         substring(n from ' ((?:a620|b650|b840|b850|x670|x870|a520|b450|b550|x570|h610|b660|b760|z690|z790|h810|b860|z890'
                          '|h310|b360|b365|z390|h370|h410|b460|z490|h510|b560|z590'
                          '|h110|b150|h170|z170|b250|h270|z270)[a-z0-9]{0,2}) ') AS chipset,
         n ~ ' (geforce|quadro|rtx[a-z0-9]*|gtx[a-z0-9]*|gt[0-9]{3,4}[a-z0-9]*) | gt [0-9]{3,4}[a-z0-9]* ' AS gpu_nvidia,
         n ~ ' rx[0-9]{3,4}[a-z0-9]* | rx [0-9]{3,4}[a-z0-9]* '                                          AS gpu_amd,
         n ~ ' arc (pro )?[ab][0-9]{2,3}[a-z0-9]* '                                                           AS gpu_intel,
         -- the GPU keywords of GarmentTaxonomy.KW_GPU: a bare " rx " or " arc " is not one
         n ~ ('gpu|tarjeta de video|video card|graphics card| rtx | gtx | rx [45679]|radeon|geforce'
              '| arc (?:a3|a5|a7|b5|pro )|placa de video|placa video')                                       AS kw_gpu,
         n ~ 'auricular|headset|headphone|earphone|earbud|inalambrico bt|over ear|in ear|on ear'              AS kw_auricular,
         n ~ ' (?:mouse pad|mousepad|pad mouse|alfombrilla mouse) '                                         AS kw_mousepad,
         n ~ ' (?:teclados?|keyboard) '                                                                     AS kw_teclado,
         n ~ ' (?:mouse|raton) (?:gamer|gaming|inalambrico|bluetooth|rgb) | gaming mouse '
           OR (n ~ ' mouse ' AND n !~ 'mickey|minnie|disney|mouse de ')                                     AS kw_mouse,
         n ~ ' (?:reloj|relojes|smartwatch|smart watch|smartband|smart band) '                              AS kw_reloj,
         n ~ ' (?:gamers?|gaming|quantum|hyperx|virtuoso|redragon|astro|razer|arctis|fantech|cougar|aula|gxt|rog|tuf'
             '|afterglow|pdp|vsg|raptor|surround|rgb|gx|7 1|g pro|hs g[0-9]+[a-z]*) '
           OR (n ~ ' corsair ' AND n ~ ' hs[0-9]+[a-z]* ')
           OR (n ~ ' logitech ' AND n ~ ' g[0-9]{3}[a-z]* ')
           OR (n ~ ' cooler master ' AND n ~ ' ch[0-9]+[a-z]* ')                                            AS auric_gamer,
         n ~ ' zone '
           OR (n ~ ' logitech ' AND n ~ ' h[0-9]{3}[a-z]* ')
           OR (n ~ ' genius ' AND n ~ ' hs [0-9]+[a-z]* ')                                                  AS auric_oficina
    FROM base
), destino AS (
  -- The categoria TechClassifier gives the name today (the leading noun beats a GPU / headset keyword).
  SELECT url, n, categoria AS origen,
         CASE WHEN categoria IN ('CPU', 'GPU', 'RAM') AND n ~ '^ combo (?:de )?actualizacion ' THEN 'Combo de Actualización'
              WHEN categoria IN ('GPU', 'Auricular', 'Joystick') AND n ~ '^ (?:soporte|soportes|rig|estructura) '
                   AND (kw_gpu OR kw_auricular) THEN 'Otros'
              WHEN categoria = 'GPU' THEN
                CASE WHEN n ~ '^ (?:cooler|watercooler|water cooler|water cooling|watercooling|aio|pasta) ' THEN 'Cooler'
                     WHEN n ~ '^ gabinete '  THEN 'Gabinete'
                     WHEN n ~ '^ fuente '    THEN 'Fuente'
                     WHEN n ~ '^ mouse '     THEN 'Mouse'
                     WHEN NOT kw_gpu         THEN 'Otros'
                     ELSE 'GPU' END
              WHEN categoria = 'Auricular' AND n ~ '^ my arcade ' THEN 'Consola'
              WHEN categoria = 'Auricular' AND n !~ '^ (?:auricular|auriculares) '
                   AND (n ~ 'headphones? jack' OR n ~ '^ (?:combo|kit|mouse|teclado|soporte|reloj) ') THEN
                CASE WHEN kw_mousepad THEN 'Mousepad'
                     WHEN kw_teclado  THEN 'Teclado'
                     WHEN kw_mouse    THEN 'Mouse'
                     WHEN kw_reloj    THEN 'Reloj'
                     ELSE 'Otros' END
              ELSE categoria END AS categoria,
         socket_explicito, chipset, gpu_nvidia, gpu_amd, gpu_intel, auric_gamer, auric_oficina
    FROM plataforma
), nuevo AS (
  SELECT url, categoria,
         CASE
           WHEN categoria <> origen THEN -- moved: the sub the resolver gives the new categoria
             CASE categoria
               WHEN 'Cooler' THEN
                 CASE WHEN n ~ ' (?:water|aio|liquida|watercooling) ' THEN 'líquida'
                      WHEN n ~ ' (?:gabinete|case) '                  THEN 'gabinete'
                      WHEN n ~ ' (?:grasa|pasta) '                    THEN 'pasta'
                      WHEN n ~ ' (?:aire|torre|disipador) '           THEN 'aire'
                      ELSE '' END
               WHEN 'Mouse' THEN
                 CASE WHEN n ~ ' (?:inalambrico|wireless|bluetooth) ' THEN 'inalámbrico'
                      WHEN n ~ ' (?:gamer|gaming) '                   THEN 'gamer'
                      ELSE '' END
               WHEN 'Teclado' THEN
                 CASE WHEN n ~ ' (?:mecanico|mechanical) '            THEN 'mecánico'
                      WHEN n ~ ' (?:inalambrico|wireless|bluetooth) ' THEN 'inalámbrico'
                      WHEN n ~ ' (?:gamer|gaming) '                   THEN 'gamer'
                      ELSE '' END
               WHEN 'Fuente' THEN
                 CASE WHEN n ~ ' modular '                            THEN 'modular'
                      WHEN n ~ ' (?:plus|bronze|gold|platinum) '      THEN '80 plus'
                      ELSE '' END
               WHEN 'Reloj' THEN
                 CASE WHEN n ~ ' (?:smartwatch|smart|inteligente) '   THEN 'smartwatch'
                      WHEN n ~ ' (?:deportivo|running|gps) '          THEN 'deportivo'
                      ELSE '' END
               ELSE '' END -- Gabinete, Consola, Mousepad, Combo de Actualización, Otros: no sub
           WHEN categoria = 'CPU' THEN
             CASE WHEN socket_explicito <> '' THEN socket_explicito
                  WHEN n ~ 'ryzen [0-9]+ [3-9][0-9]{3}' THEN
                    CASE WHEN substring(n from 'ryzen [0-9]+ ([3-9])[0-9]{3}') >= '7' THEN 'am5' ELSE 'am4' END
                  WHEN n ~ 'ultra [0-9]+ 2[0-9]{2}'     THEN 'lga1851'
                  WHEN n ~ 'i[3579] 1[234][0-9]{2}'     THEN 'lga1700'
                  WHEN n ~ 'i[3579] 1[01][0-9]{2}'      THEN 'lga1200'
                  WHEN n ~ 'i[3579] [6-9][0-9]{3}'      THEN 'lga1151'
                  WHEN n ~ ' (?:pentium|celeron) ' AND n ~ ' g(?:4[0-9]{3}|5[0-4][0-9]{2})[a-z]* ' THEN 'lga1151'
                  WHEN n ~ ' (?:pentium|celeron) ' AND n ~ ' g(?:59|64)[0-9]{2}[a-z]* '            THEN 'lga1200'
                  WHEN n ~ ' (?:pentium|celeron) ' AND n ~ ' g74[0-9]{2}[a-z]* '                    THEN 'lga1700'
                  WHEN n ~ ' athlon ' AND n ~ ' (3000g|200ge|220ge|240ge|300ge|320ge) ' THEN 'am4'
                  ELSE '' END
           WHEN categoria = 'Motherboard' THEN
             CASE WHEN socket_explicito <> '' THEN socket_explicito
                  WHEN left(chipset, 4) IN ('a620', 'b650', 'b840', 'b850', 'x670', 'x870') THEN 'am5'
                  WHEN left(chipset, 4) IN ('a520', 'b450', 'b550', 'x570') THEN 'am4'
                  WHEN left(chipset, 4) IN ('h610', 'b660', 'b760', 'z690', 'z790') THEN 'lga1700'
                  WHEN left(chipset, 4) IN ('h810', 'b860', 'z890') THEN 'lga1851'
                  WHEN left(chipset, 4) IN ('h410', 'b460', 'z490', 'h510', 'b560', 'z590') THEN 'lga1200'
                  WHEN left(chipset, 4) IN ('h310', 'b360', 'b365', 'z390', 'h370', 'h110', 'b150', 'h170', 'z170',
                                            'b250', 'h270', 'z270') THEN 'lga1151'
                  ELSE '' END
           WHEN categoria = 'RAM' THEN
             CASE WHEN n ~ ' (?:sodimm|so dimm|notebook|laptop) ' THEN 'notebook'
                  WHEN n ~ ' (?:ddr5|dddr5) '                     THEN 'ddr5'
                  WHEN n ~ ' (?:ddr4|dddr4) '                     THEN 'ddr4'
                  WHEN n ~ ' ddr3 '                               THEN 'ddr3'
                  WHEN n ~ ' (?:ddr2|ddr) '                       THEN ''
                  -- no DDR token: the speed names the generation (DDR3 <= 1866, DDR4 2133-3600, DDR5 >= 4800)
                  WHEN n ~ '[0-9]{3,5} ?(?:mhz|mts|mt s|mt) ' THEN
                    CASE WHEN substring(n from '([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ')::int >= 4800 THEN 'ddr5'
                         WHEN substring(n from '([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ')::int BETWEEN 2133 AND 3600 THEN 'ddr4'
                         WHEN substring(n from '([0-9]{3,5}) ?(?:mhz|mts|mt s|mt) ')::int <= 1866 THEN 'ddr3'
                         ELSE '' END
                  ELSE '' END
           WHEN categoria = 'GPU' THEN
             CASE WHEN gpu_nvidia OR n ~ ' (?:nvidia|[0-9]{3,4}gt) ' THEN 'nvidia'
                  WHEN gpu_amd OR n ~ ' (?:radeon|r[579] [0-9]{3}[a-z0-9]*) ' THEN 'amd'
                  WHEN gpu_intel THEN 'intel'
                  ELSE '' END
           WHEN categoria = 'Auricular' THEN
             CASE WHEN n ~ ' (?:earbuds?|buds|tws|true wireless) '                        THEN 'in-ear'
                  WHEN n ~ ' (?:inalambric[a-z0-9]*|wireless|wireles|bluetooth|bluetooh) ' THEN 'inalámbrico'
                  WHEN auric_gamer                                                          THEN 'gamer'
                  WHEN auric_oficina                                                        THEN 'oficina'
                  WHEN n ~ ' (?:in ear|earphones?|intraural) '                              THEN 'in-ear'
                  ELSE '' END
           WHEN categoria = 'PC' THEN -- a dedicated GPU makes it gamer; a bare "radeon" is the CPU's integrated graphics
             CASE WHEN gpu_nvidia OR gpu_amd OR gpu_intel THEN 'gamer' ELSE 'oficina' END
         END AS sub -- NULL: Joystick that did not move, not ours
    FROM destino
)
UPDATE productos
   SET categoria = CASE WHEN nuevo.sub = 'otro' THEN productos.categoria ELSE nuevo.categoria END,
       sub_categoria = CASE WHEN nuevo.sub = 'otro' THEN '' ELSE nuevo.sub END
  FROM nuevo
 WHERE productos.url = nuevo.url
   AND nuevo.sub IS NOT NULL
   AND (productos.sub_categoria IS DISTINCT FROM CASE WHEN nuevo.sub = 'otro' THEN '' ELSE nuevo.sub END
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
   AND categoria IN ('Gabinete', 'Almacenamiento', 'Red', 'Cable', 'Impresión', 'Cooler', 'Fuente', 'Teclado', 'Mouse',
                     'Monitor', 'Notebook', 'Joystick', 'Cámara', 'Videojuego', 'Mousepad', 'Combo de Actualización',
                     'Silla', 'TV y Audio', 'Micrófono', 'Tablet', 'Webcam', 'Consola', 'Mini PC', 'UPS',
                     'Soporte Monitor', 'Escritorio');
