-- V56__prenda_lider_fuera_de_tech.sql
--
-- CategoryClassifier evaluated TECH before clothing, so a hardware token later in a clothing
-- name won: "Zapatillas … Gtx" (Gore-Tex) was a GPU, "Mochila … Notebook" a Notebook, "Sweater
-- Canguro Mother" a Motherboard. KW_PRENDA_LIDER now skips TECH; this aligns the rows already
-- stored (37 active on dev, 2026-10-27) instead of waiting for every site to be scraped again.
--
-- The target is the coarse canonical category for each leading noun; the next scrape may refine
-- it (Zapatilla → Zapatilla Running) and is the source of truth. Only rows in a category TECH can
-- return move, plus loncheras sitting in Otros, never a row locked by hand (V3). sub_categoria is
-- resolved from (nombre, categoria), so the old value is reset to the abstention ''. A shoe is
-- never gymrat (GymratTagger's calzado guard). One statement: atomic, and idempotent because a
-- moved row no longer sits in a source category.
UPDATE productos p
   SET categoria     = d.destino,
       sub_categoria = '',
       gymrat        = CASE WHEN d.destino IN ('Zapatilla', 'Borcego') THEN false ELSE p.gymrat END
  FROM (
        SELECT url,
               CASE
                 WHEN categoria = 'Otros' THEN
                   CASE WHEN n ~ '^lonchera ' THEN 'Bolso' END
                 WHEN n ~ '^zapatilla' THEN
                   CASE WHEN n ~ '(borcego|hiker|hiking|work boot|martens|1460|chunky boot|plataforma alta|lug sole|bota alta|boot alta)'
                        THEN 'Borcego' ELSE 'Zapatilla' END
                 WHEN n ~ '^remera '              THEN 'Remera'
                 WHEN n ~ '^mochila '             THEN 'Mochila'
                 WHEN n ~ '^sweater '             THEN 'Sweater'
                 WHEN n ~ '^(campera |chaqueta)'  THEN 'Campera'
                 WHEN n ~ '^pelota'               THEN 'Pelota'
                 WHEN n ~ '^lonchera '            THEN 'Bolso'
                 WHEN categoria = 'Tablet' AND n ~ ' tablets ' AND n !~ '(^| )tablet ' THEN
                   CASE WHEN n ~ 'vitamin' THEN 'Vitaminas' ELSE 'Otros' END
               END AS destino
          FROM (SELECT url, categoria, lower(btrim(nombre)) || ' ' AS n
                  FROM productos
                 WHERE bloqueado_por IS NULL
                   AND categoria IN ('Almacenamiento','Auricular','CPU','Cable','Cooler','Cámara',
                                     'Fuente','GPU','Gabinete','Impresión','Joystick','Micrófono',
                                     'Mini PC','Monitor','Motherboard','Mouse','Mousepad','Notebook',
                                     'PC','RAM','Red','Reloj','Tablet','Teclado','UPS','Webcam',
                                     'Otros')) candidatos
       ) d
 WHERE p.url = d.url
   AND d.destino IS NOT NULL;
