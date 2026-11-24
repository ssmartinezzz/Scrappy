-- V135__accesorio_gaming_y_subs_de_consola.sql
--
-- 1. New category 'Accesorio Gaming' (tecnologia; GamingClassifier -> GamingReglas.esAccesorio): what is sold for a console or a controller
-- (cases, protectors, grips, docks, charging stands, adapters, AV and charging cables, card readers, gift cards, cooling stands, the PS5
-- disc drive, PlayStation Portal). A new category is TWO changes: the classifier produces it, and this row lets productos.categoria
-- (FK fk_productos_categoria, V13) accept it. Without the row every upsert of such a product violates the FK, ProductRepository swallows
-- the error and the symptom is "0 nuevos". CategoriaLookupTableTest requires this table and CategoryGroups.canonicalCategories() to be the same set.
SET CONSTRAINTS ALL IMMEDIATE;

INSERT INTO categoria (nombre) VALUES ('Accesorio Gaming')
ON CONFLICT (nombre) DO NOTHING;

-- 2. Moves. The same regexes as GamingReglas over the same tokens (the name lower-cased, accent-folded, every run of non [a-z0-9] one space,
-- space-padded: "Joy-Con (L/R)" is ' joy con l r '), so a platform glued to punctuation counts. V135GamingAgreementTest runs this SQL and the Java
-- over real names. renormalizar feeds the stored categoria back as raw, so a rule that only says "not this category" never moves a stored row: this does.
--   fuera       stored Consola / Videojuego / Joystick that is no gaming product: a cap -> Gorra; a bottle or a guitar pedalboard -> Otros
--   acc         platform word + accessory noun -> Accesorio Gaming. A weak noun (stand, cover, camera, card...) also names games, so a title
--               that ends in " - <platform>" is a game; a controller word before the accessory noun ("Wireless Controller and Slim Case") keeps
--               it a controller unless the noun follows the controller word directly ("Controller Adapter") or is a charging grip / battery pack
--   consola     a console named at the start ("Consola Retro ...", "New 3DS XL ...", "Retron 2")
--   joystick    a controller word + platform, or racing gear + platform, not sold as a "bundle" with a game
--   videojuego  an explicit game platform ("(Nintendo Switch)", "-PS4", a trailing "Switch") from Otros only
-- One regex per row, each written once (the fallbacks of Java read the same rows). Sources are the categories the measured rows came from
-- (dev DB 2026-11-21); a row locked by hand (bloqueado_por) is never touched. Rubro: the one RubroResolver gives a tech category (a site with a
-- forced rubro keeps it, any other is tecnologia); a cap is indumentaria. Active and inactive rows alike. Idempotent: a moved row is in no source rule twice.
WITH rx (clave, re) AS (VALUES
  ('plataforma', ' (?:ps1|ps2|ps3|ps4|ps5|psp|ps vita|psvita|ps vr|ps vr2|psvr|psvr2|playstation|xbox|nintendo|switch|joy con|joycon|dualsense|dualshock|steam deck|rog ally|legion go|msi claw|3ds|2ds|dsi|nds|wii|game boy|gameboy|gamecube|game cube|n64|snes|nes|famicom|sega|dreamcast|saturn|master system|atari|retro bit|retrobit|ns2|sw2) '),
  ('fuerte', 'funda|fundas|estuche|estuches|case|cases|carrying|travel bag|travel case|hard shell|pouch|protector|protectores|protection|grip|grips|dock|docking station|soporte|soportes|carcasa|skin|skins|adapter|adaptador|converter|conversor|cargador|charger|cable|cables|battery|bateria|tempered glass|vidrio templado|ventilador|enfriador|enfriamiento|refrigeracion|unidad lectora|unidad de disco|disc drive|armor|antideslizante|sensor bar|barra sensora|gift card|membresia|membership|base de carga|reproductor remoto|playstation portal|ps portal|joy con 2 wheel|joycon 2 wheel|leg strap|analogicos|boost pak|jumper pack|action replay|screen filter|cover plates|hub|cubierta|cubiertas|accesorio|accesorios'),
  ('debil', 'stand|cover|covers|charging|charge|cooler|camera|camara|memory card|memoria|reader|lector|nfc|eshop|tarjeta|estacion'),
  ('pista', ' (?:original cover|first print cover|cover reversible|reversible cover|limited run|pouch de regalo) '),
  ('de_control', ' (?:charging grip|charge grip|comfort grip|charge stand|charge station|charging station|charging case|usb adapter|wireless adapter|battery pack) '),
  ('control', 'controller|controllers|control|controles|controlador|controladores|mando|mandos|gamepad|joypad|joy pad|joystick|joysticks|jostick|joy con|joycon|dualsense|dualshock|horipad|pro controller|arcade stick|fight stick'),
  ('ajeno', ' (?:kvm|gigabit|poe|ethernet|rj45|administrable|sfp|tp link|tplink|router|modem|cat5e|cat6|dimmer|interruptor|domotica|hdmi switch|switch hdmi|usb hub|hub usb|keycap|keycaps|mecanico|teclado|keyboard|cuaderno|notebook|notebooks|maceta|macetas|zapatilla|multiplicador|figure|figures|figura|figuras|collectible|collectibles|diorama|playset|toy|slot car|cricut|cooler master) '),
  ('juego_al_final', ' - (?:(?:nintendo|sony|microsoft) )?(?:switch 2|switch|ps5|ps4|ps3|ps2|ps1|playstation [1-5]|playstation vita|ps vita|psvita|3ds|2ds|ds|wii u|wii|xbox one|xbox 360|xbox series x|xbox series s|gamecube|n64)\)? $'),
  ('fuera', ' (?:gorra|snapback|bottle|botella|pedalera de efectos|pedalera de multi efectos|footswitch|escritorio|impresora 3d) '),
  ('gorra', ' (?:gorra|snapback) '),
  ('descarte', ' (?:bottle|botella|pedalera de efectos|pedalera de multi efectos|footswitch) '),
  ('consola', '^ (?:consola|console) (?:de juegos |portable |portatil |digital |sony |nintendo )*(?:retro|r36s|legion go|steam deck|rog ally|family|game stick|emuladora|emulador|playstation|ps5|ps4|xbox|switch|nintendo 64|nintendo switch) |^ (?:nintendo )?(?:new 3ds|new 2ds|wii mini|super nintendo classic|snes classic|nes classic|nintendo classic mini|hyperkin retron|retron|supaboy|retro duo) (?!.*controllers? )|^ nintendo game watch |^ (?:paquete )?nintendo switch 2 (?:mario kart world bundle|bundle|elija su juego|console) '),
  ('control_de_plataforma', ' (?:controller|controllers|gamepad|joypad|joy pad|joy con|joycon|dualsense|dualshock|horipad|pro controller|split pad|controles|mando|mandos|joystick|joysticks|jostick|joystck|controlador|controladores) '),
  ('periferico', ' (?:headset|headsets|auricular|auriculares|headphone|headphones|volante|wheel|pedalera|pedal|pedales|bolso|mochila|llavero|keychain|hanger|sticker|sleeve|paladone|mantel|vaso|taza|mug|fanatec|motor base|cockpit|sensor|teclado|keyboard|mouse|monitor|hdmi|ssd|disco duro|hdd|pantalla|silla|capturadora|capture card|parlante|lampara|lamp|microsd|sd card|power stick|memoria|bateria|battery) '),
  ('paquete', ' bundle '),
  ('carrera', ' (?:motor base|volante|pedalera|racing wheel) '),
  ('juego_plataforma', ' (?:nintendo switch|switch 2|ps5|ps4|ps3|ps2|ps1|playstation [1-5]|playstation4|playstation5|playstation vita|ps vita|psvita|psp|nintendo 3ds|3ds|2ds|nintendo ds|wii u|wii|xbox one|xbox 360|xbox series x|xbox series s|gamecube|game boy advance|famicom|ns2) '),
  ('switch_al_final', ' switch $')
), sep AS (
  SELECT ','::text AS sep
), k AS (
  SELECT 'Accesorio Gaming'::text AS acc, 'Consola'::text AS con, 'Videojuego'::text AS vid, 'Joystick'::text AS joy,
         'Gorra'::text AS gorra, 'Otros'::text AS otros,
         string_to_array('Otros,Consola,Videojuego,Joystick,Cable,Almacenamiento,Cooler,Celular,Auricular,Webcam,Red,Fuente,Organización,Bolso,Zapatilla Skate', sep.sep) AS origen,
         string_to_array('Otros,Videojuego,Joystick', sep.sep) AS origen_consola,
         string_to_array('Otros,Videojuego,Fuente,Bolso', sep.sep) AS origen_joystick,
         'tecnologia'::text AS tec, 'indumentaria'::text AS ind
    FROM sep
), rg0 AS (
  SELECT max(CASE WHEN clave = 'plataforma' THEN re END) AS plat,
         max(CASE WHEN clave = 'fuerte' THEN re END) AS fuerte_alt,
         max(CASE WHEN clave = 'debil' THEN re END) AS debil_alt,
         max(CASE WHEN clave = 'pista' THEN re END) AS pista,
         max(CASE WHEN clave = 'de_control' THEN re END) AS de_control,
         max(CASE WHEN clave = 'control' THEN re END) AS control,
         max(CASE WHEN clave = 'ajeno' THEN re END) AS ajeno,
         max(CASE WHEN clave = 'juego_al_final' THEN re END) AS juego_al_final,
         max(CASE WHEN clave = 'fuera' THEN re END) AS fuera,
         max(CASE WHEN clave = 'gorra' THEN re END) AS gorra,
         max(CASE WHEN clave = 'descarte' THEN re END) AS descarte,
         max(CASE WHEN clave = 'consola' THEN re END) AS consola,
         max(CASE WHEN clave = 'control_de_plataforma' THEN re END) AS control_de_plataforma,
         max(CASE WHEN clave = 'periferico' THEN re END) AS periferico,
         max(CASE WHEN clave = 'paquete' THEN re END) AS paquete,
         max(CASE WHEN clave = 'carrera' THEN re END) AS volante,
         max(CASE WHEN clave = 'juego_plataforma' THEN re END) AS juego_plataforma,
         max(CASE WHEN clave = 'switch_al_final' THEN re END) AS switch_al_final
    FROM rx
), rg1 AS (
  -- the accessory nouns: the strong ones alone, and with the weak ones (GamingReglas.ACCESORIO_FUERTE / ACCESORIO_DEBIL)
  SELECT r.*, ' (?:' || r.fuerte_alt || '|' || r.debil_alt || ') ' AS acc, ' (?:' || r.fuerte_alt || ') ' AS fuerte
    FROM rg0 r
), rg AS (
  -- a controller word that comes before every accessory noun and is not followed by one: the same text as GamingReglas.P_CONTROL_ANTES_DE_ACCESORIO
  SELECT r.*, '^(?:(?!' || r.acc || ').)*(?!' || r.acc || ') (?:' || r.control || ')(?!' || r.acc || ') ' AS control_antes
    FROM rg1 r
), base AS (
  SELECT p.url, p.categoria, p.rubro, COALESCE(s.rubro_forzado, '') AS forzado,
         ' ' || lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')) || ' ' AS n
    FROM productos p
    LEFT JOIN sitio s ON s.sitio_key = p.sitio_key
    CROSS JOIN k
   WHERE p.bloqueado_por IS NULL
     AND p.categoria = ANY (k.origen)
), tok AS (
  SELECT b.*, ' ' || btrim(regexp_replace(b.n, '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
    FROM base b
), cand AS (
  SELECT t.* FROM tok t CROSS JOIN rg
   WHERE t.t ~ rg.plat OR t.t ~ rg.juego_plataforma OR t.t ~ rg.consola OR t.t ~ rg.fuera
), rasgo AS (
  SELECT c.url, c.categoria, c.rubro, c.forzado,
         c.t ~ rg.fuera AS fuera, c.t ~ rg.gorra AS gorra, c.t ~ rg.descarte AS descarte,
         CASE WHEN c.t ~ rg.plat AND c.t ~ rg.acc AND NOT c.t ~ rg.ajeno AND NOT c.t ~ rg.pista
                   AND (c.t ~ rg.fuerte OR NOT c.n ~ rg.juego_al_final)
              THEN c.t ~ rg.de_control OR NOT c.t ~ rg.control_antes
              ELSE false END AS es_accesorio,
         c.t ~ rg.consola AS es_consola,
         ((c.t ~ rg.control_de_plataforma AND c.t ~ rg.plat AND NOT c.t ~ rg.paquete AND NOT c.t ~ rg.ajeno AND NOT c.t ~ rg.periferico)
           OR (c.t ~ rg.volante AND c.t ~ rg.plat AND NOT c.t ~ rg.ajeno)) AS es_joystick,
         ((c.t ~ rg.juego_plataforma OR c.t ~ rg.switch_al_final) AND NOT c.t ~ rg.ajeno AND NOT c.t ~ rg.periferico) AS es_videojuego
    FROM cand c CROSS JOIN rg
), destino AS (
  SELECT r.url, r.rubro, r.forzado,
         CASE WHEN r.fuera THEN CASE WHEN r.categoria IN (k.con, k.vid, k.joy)
                                     THEN CASE WHEN r.gorra THEN k.gorra WHEN r.descarte THEN k.otros END END
              WHEN r.es_accesorio THEN k.acc
              WHEN r.es_consola AND r.categoria = ANY (k.origen_consola) THEN k.con
              WHEN r.es_joystick AND (r.categoria = ANY (k.origen_joystick)) THEN k.joy
              WHEN r.es_videojuego AND r.categoria = k.otros THEN k.vid
         END AS nueva
    FROM rasgo r CROSS JOIN k
)
UPDATE productos p
   SET categoria = d.nueva,
       sub_categoria = '',
       rubro = CASE WHEN d.nueva = k.gorra THEN k.ind
                    WHEN d.nueva = k.otros THEN p.rubro
                    WHEN d.forzado <> '' THEN d.forzado
                    ELSE k.tec END
  FROM destino d CROSS JOIN k
 WHERE p.url = d.url
   AND d.nueva IS NOT NULL
   AND p.categoria IS DISTINCT FROM d.nueva;

-- 3. Sub of the three gaming categories, by platform (SubcategoryResolver -> GamingReglas.sub: the first rule of the group that matches wins,
-- '' when none does; an unnamed platform stays empty, never a guess). grupo 1 Consola (pc portátil, emulador tv, emulador portátil, retro, playstation, xbox, nintendo switch,
-- nintendo), 2 Videojuego (switch 2, switch, ps5, ps4, playstation retro, xbox, nintendo 3ds, wii, pc, retro), 3 Joystick (volante, gamepad).
-- renormalizar rewrites sub_categoria from the same rules. Active and inactive rows alike; a row locked by hand is not touched.
WITH regla (grupo, prioridad, sub, re) AS (VALUES
  (1, 1, 'pc portátil', ' (?:steam deck|rog ally|legion go|msi claw|ayaneo|ayn odin|onexplayer|gpd win) '),
  (1, 2, 'emulador tv', ' (?:game stick|gamestick|tv stick|emuladora|retroplay|retro play|k8|x8 pro) '),
  (1, 3, 'emulador portátil', '^(?!.* (?:switch|swithc|steam deck) )(?:.* (?:powkiddy|anbernic|miyoo|retroid|trimui|r36s|rebak|retroboy) |(?=.* portatil ).* (?:retro|sup|[0-9]+ bits?|[0-9]+ juegos|[0-9]+ en 1) )'),
  (1, 4, 'emulador tv', ' (?:family|nes av|mini drive|game drive|m8 hdmi|sega hdmi) '),
  (1, 5, 'retro', ' (?:retro|anbernic|powkiddy|miyoo|retroid|game stick|gamestick|tv stick|nes mini|snes mini|nes classic|snes classic|super nintendo classic|genesis mini|neo geo|classic mini|ps classic|playstation classic|flashback|emulador|emuladora|fichines|joyplay|retroboy|r36s|my arcade|atari|sega|famicom|supaboy|retron|retro duo|family|[0-9]+ en 1|[0-9]+ bits?) '),
  (1, 6, 'playstation', ' (?:ps1|ps2|ps3|ps4|ps5|psp|ps vita|psvita|playstation|ps portal) '),
  (1, 7, 'xbox', ' xbox '),
  (1, 8, 'nintendo switch', ' (?:switch|swithc|nsw|ns2|sw2) '),
  (1, 9, 'nintendo', ' (?:nintendo|3ds|2ds|dsi|nds|wii|game boy|gameboy|gba|gamecube|n64|snes|nes|virtual boy) '),
  (2, 1, 'switch 2', ' (?:switch 2|switch2|ns2|sw2) '),
  (2, 2, 'switch', ' (?:switch|nsw) '),
  (2, 3, 'ps5', ' (?:ps5|playstation 5|playstation5) '),
  (2, 4, 'ps4', ' (?:ps4|playstation 4|playstation4) '),
  (2, 5, 'playstation retro', ' (?:ps3|ps2|ps1|ps vita|psvita|playstation vita|psp|playstation 3|playstation 2|playstation3|playstation2|playstation portable) '),
  (2, 6, 'xbox', ' xbox '),
  (2, 7, 'nintendo 3ds', ' (?:3ds|2ds|nintendo ds|nds|dsi) '),
  (2, 8, 'wii', ' (?:wii|wii u) '),
  (2, 9, 'pc', ' (?:pc|steam|windows) '),
  (2, 10, 'retro', ' (?:nes|snes|super nintendo|n64|nintendo 64|gamecube|game cube|game boy|gameboy|gba|sega|genesis|mega drive|dreamcast|saturn|master system|atari|famicom|neo geo) '),
  (3, 1, 'volante', ' (?:volante|volantes|racing|driving|pedalera|pedales|pedal|shifter|palanca de cambios|handbrake|freno de mano|simracing|sim racing|fanatec|moza) '),
  (3, 2, 'gamepad', ' (?:gamepad|gamepads|joystick|joysticks|jostick|joystck|joypad|joy pad|joy con|joycon|controller|controllers|control|controles|controlador|controladores|mando|mandos|dualsense|dualshock|pro controller|horipad|split pad|nunchuk|pad) ')
), grupo (grupo, categoria) AS (
  VALUES (1, 'Consola'), (2, 'Videojuego'), (3, 'Joystick')
), tok AS (
  SELECT p.url, g.grupo,
         ' ' || btrim(regexp_replace(lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')),
                                     '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
    FROM productos p
    JOIN grupo g ON g.categoria = p.categoria
   WHERE p.bloqueado_por IS NULL
), nuevo AS (
  SELECT t.url,
         COALESCE((SELECT r.sub FROM regla r WHERE r.grupo = t.grupo AND t.t ~ r.re ORDER BY r.prioridad LIMIT 1), '') AS sub
    FROM tok t
)
UPDATE productos p
   SET sub_categoria = nuevo.sub
  FROM nuevo
 WHERE p.url = nuevo.url
   AND p.sub_categoria IS DISTINCT FROM nuevo.sub;
