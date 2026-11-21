-- V129__subcategoria_iluminacion_mueble.sql
--
-- Gives sub_categoria to 'Iluminación' (Lamparita, Velador, Colgante, Plafón, Spot, Reflector...) and 'Mueble' (Alacena, Bajo Mesada,
-- Despensero, Rack de TV, Mesa de Luz...), which had none. The labels are the words Argentine retail uses. As in V128 the LEADING
-- NOUN decides: "Lámpara Colgante" is a Colgante, "Lámpara E27 9 W" a Lamparita (the bulb), "Led Plafón Redondo" a Plafón and "Mueble
-- Para Tv" a Rack de TV; a "Combo" or "Set" of kitchen furniture is a Cocina Modular. A name no rule claims keeps an empty sub.
--
-- Same rule as HogarSubcategoryResolver (Java) over ' ' || name || ' ', normalized and stripped of the sale prefix exactly as in V128
-- ("hue" is also skipped here, so "Philips Hue Plafón" leads with "plafon"). V129HogarAgreementTest runs both over the same names.
--
-- One single-line text blob, rows 'cat|sub|leaders|any|veto' joined by ';' and words by ',' (no raw newline: Sonar flags one inside a
-- literal). Rows are tried in order and the first that fires wins: the name must START with a leader, contain one of 'any' when
-- present and none of 'veto'. Rows locked by hand (bloqueado_por) are not touched; active and inactive rows alike; only a row whose
-- sub changes is written. SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE.
SET CONSTRAINTS ALL IMMEDIATE;

UPDATE productos p
   SET sub_categoria = q.sub
  FROM (
    SELECT c.url, c.sub
      FROM (
        SELECT d.url,
               COALESCE((SELECT v.sub
                           FROM unnest(string_to_array('il|Lámpara de Pie| lampara , lamp , luz , luminaria , led | de pie , pie | escritorio , mesa ;il|Colgante| colgante , colgantes , arana , aranas ;il|Colgante| lampara , lamp , luminaria | colgante , colgar ;il|Aplique| aplique , apliques , tortuga , tortugas ;il|Aplique| lampara , lamp , luminaria | pared , aplique | pie ;il|Velador| velador , veladores ;il|Velador| lampara , lamp , luminaria | escritorio , escr , esc , mesa , velador , noche , lectura , pinza , cabecera , infantil ;il|Velador| luz | velador , noche ;il|Spot| spot , spots , dicrospot , cabezal , cabezales , riel , rieles , dicro ;il|Spot| lampara , lamp | riel ;il|Spot| difusor , difusores | gu10 ;il|Plafón| plafon , plafones , plafoniere ;il|Plafón| led , luz , lampara , luminaria | plafon , plafones ;il|Reflector| reflector , reflectores , reflectora , reflectoras , proyector , proyectores || planetario , galaxia , estrellas , xgimi , astronauta , sunset , atardecer , laser , epson , ansi , hdmi ;il|Reflector| led , luz | reflector , reflectores , reflectora , proyector , proyectores | e27 , e14 , cultivo ;il|Panel y Listón| panel , paneles , liston , listones , tubo , tubos || matrix , auto , camioneta , solar ;il|Tira LED| tira , tiras , cinta led , cintas led , manguera led , manguera luminosa ;il|Guirnalda| guirnalda , guirnaldas , cortina , cortinas , serie de luces , cadena de luces ;il|Farol y Estaca| farol , faroles , farola , farolas , estaca , estacas , baliza , balizas , luz tipo farol ;il|Luz de Emergencia| luz emergencia , luz de emergencia , luces de emergencia , lampara de emergencia , lampara emergencia , luminaria de emergencia , luminaria emergencia , emergencia ;il|Portalámpara| portalampara , portalamparas , porta lampara , floron , florones ;il|Lamparita| lamparita , lamparitas , bombilla , bombillas , bulbo , bulbos , dicroica , dicroicas , ar111 , par38 , par30 ;il|Lamparita| lampara , lamparas , lamp | e27 , e14 , gu10 , g9 , gu5 , g4 , r7s , b22 , mr16 , dicroica , filamento , bulbo , globo , vela , t10 , t8 , 12v , smart , foco , focos ;il|Lamparita| luz | bulbo , e27 , gu10 , dicroica , filamento ;il|Lamparita| led || lineal , difuso , panel , tira , spot ;mu|Cocina Modular| cocina , cocinas , muebles de cocina , mueble de cocina, muebles cocina , mueble cocina , completo de cocina ;mu|Bajo Mesada| bajo mesada, bajos mesada , mueble bajo mesada, modulo bajo mesada, modulo bajomesada , muebles bajo mesada, mueble de bajo mesada , juego de bajo mesada , bajomesada ;mu|Alacena| alacena , alacenas , mueble alacena , armario aereo , modulo alacena ;mu|Despensero| despensero , despensera , despenseros , mueble despensero ;mu|Despensero| organizador , armario , escobero | despensero ;mu|Portahorno| portahorno , portahornos , mueble para microondas , muebles para microondas , mueble porta microondas , mueble para horno , porta hornos , porta horno , porta microondas ;mu|Rack de TV| rack , racks , panel rack , panel de tv , panel tv , mesa rack , mesa tv , mesa para tv , mueble para tv , mueble tv , mueble rack , mueble de tv , centro de entretenimiento ;mu|Biblioteca| biblioteca , bibliotecas , librero , libreros , mueble biblioteca ;mu|Cómoda y Cajonera| comoda , comodas , cajonera , cajoneras , cajonero , mueble comoda ;mu|Chifonier| chifonier , chiffonier , chifonnier , sifonier ;mu|Vajillero| vajillero , vajilleros , cristalero , cristaleros , mueble vajillero ;mu|Aparador| aparador , aparadores , buffet , bahiut , bahut , credenza , mueble aparador ;mu|Mesa de Luz| mesa de luz , mesas de luz , mesita de luz , mesita de noche , mesa de noche , mesa luz , mesa mesita de luz , panel con mesa de luz , mesita , velador ;mu|Zapatero| zapatero , zapateros , zapatera , botinero , botineros , organizador de zapatos , organizador de calzado , organizador zapatero , armario zapatero ;mu|Rack de TV| mueble , muebles | rack tv , rack de tv , para tv ;mu|Zapatero| mueble , muebles | zapatero , botinero | esquinero ;mu|Cocina Modular| modular , modulo | cocina ', ';')) WITH ORDINALITY AS t(fila, orden)
                          CROSS JOIN LATERAL (SELECT split_part(t.fila, '|', 2) AS sub,
                                                     string_to_array(split_part(t.fila, '|', 3), ',') AS lider,
                                                     string_to_array(NULLIF(split_part(t.fila, '|', 4), ''), ',') AS alguna,
                                                     string_to_array(NULLIF(split_part(t.fila, '|', 5), ''), ',') AS veto) AS v
                          WHERE split_part(t.fila, '|', 1) = d.clave
                            AND EXISTS (SELECT 1 FROM unnest(v.lider) w WHERE left(d.sin, length(w)) = w)
                            AND (v.alguna IS NULL OR EXISTS (SELECT 1 FROM unnest(v.alguna) w WHERE position(w IN d.nombre) > 0))
                            AND (v.veto IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(v.veto) w WHERE position(w IN d.nombre) > 0))
                          ORDER BY t.orden LIMIT 1), '') AS sub
          FROM (
            SELECT l.url, l.clave, l.nombre,
                   regexp_replace(l.nombre, '^ (?:(?:pack|grande|chico|x|de|caja|promo|combo|outlet|m2|2x1|3m|unidades|set|kit|mini|hue|philips|x?[0-9]+(?:[.,][0-9]+)?)[[:space:]]+)+', ' ') AS sin
              FROM (
                SELECT p2.url,
                       CASE p2.categoria WHEN 'Iluminación' THEN 'il' ELSE 'mu' END AS clave,
                       ' ' || btrim(regexp_replace(translate(lower(p2.nombre), 'áàäéèëíìïóòöúùüñ/:,;+()[]', 'aaaeeeiiiooouuun         '), '[[:space:]]+', ' ', 'g')) || ' ' AS nombre
                  FROM productos p2
                 WHERE p2.bloqueado_por IS NULL
                   AND p2.categoria IN ('Iluminación', 'Mueble')
              ) l
          ) d
      ) c
  ) q
 WHERE p.url = q.url
   AND COALESCE(p.sub_categoria, '') <> q.sub;
