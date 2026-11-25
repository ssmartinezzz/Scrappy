-- V139__rubro_electrodomesticos.sql
--
-- New rubro 'electrodomesticos' (orden 11, IPC, peso_forzado 0, peso_categoria 1: weighs like hogar), as rows of the V138 tables.
--   categoria_rubro  the 11 appliance categories move from hogar (Aire Acondicionado, Aspiradora, Calefactor, Calefón, Campana y Extractor,
--                    Cocina y Horno, Heladera, Lavarropas, Pequeño Electrodoméstico, Termotanque, Ventilador) and 'TV y Audio' gets a row per sub
--                    that is a home appliance (Televisor, Parlante, Soporte, Barra de Sonido, Proyector, Radio, Tocadiscos, Equipo de Música).
--                    The sub rows decide only on a site with no forced rubro, so a forced store keeps its TV and audio. Handy, Consola y Mixer and
--                    the blank sub stay tecnologia, and categoria.indice of 'TV y Audio' stays the dollar: these products are still priced in USD.
--   sitio            Cetrogar's default rubro becomes electrodomesticos (it sells appliances; what no category decides, such as a blank category or
--                    Otros, reads as one). Samsung's stays tecnologia: its Tab covers and SmartThings sensors are technology. Its appliance spare
--                    parts (fridge shelves, drawers and ice maker, vacuum parts, microwave plates) have curated leaves that were mapped to Otros;
--                    they become 'Pequeño Electrodoméstico', which is where the leaf outranks the name (NormalizerService.SITIOS_DONDE_LA_HOJA_GANA).
-- The stored productos, active and inactive, end where RubroResolver puts them; a row locked by hand (bloqueado_por) is not touched:
--   * the 11 categories: the forced rubro when the site weighs at least as much as electrodomesticos (tecnologia and oficina), else electrodomesticos;
--   * 'TV y Audio' with one of those subs on a site with no forced rubro;
--   * on a site whose default is electrodomesticos, a row whose category has no categoria_rubro row;
--   * Samsung's spare parts: category, sub (the leading noun, as ElectroSubcategoryResolver reads it) and rubro.
-- The rubro is looked up by its orden, not repeated as a literal (Sonar S1192), and the category lists are one ';'-joined literal split here and the spare-part leaders one regex.
-- Re-runnable. The V40 trigger bumps catalog_version once at COMMIT however many rows change. No ALTER TABLE on productos, so the
-- SET CONSTRAINTS flush of V112 is not needed.
INSERT INTO rubro (clave, etiqueta, orden, indice, peso_forzado, peso_categoria)
VALUES ('electrodomesticos', 'Electrodomésticos', 11, 'IPC', 0, 1)
ON CONFLICT (clave) DO NOTHING;

UPDATE categoria_rubro
   SET rubro = (SELECT clave FROM rubro WHERE orden = 11)
 WHERE sub_categoria = ''
   AND categoria = ANY (string_to_array('Aire Acondicionado;Aspiradora;Calefactor;Calefón;Campana y Extractor;Cocina y Horno;Heladera;Lavarropas;Pequeño Electrodoméstico;Termotanque;Ventilador', ';'));

INSERT INTO categoria_rubro (categoria, sub_categoria, rubro)
SELECT 'TV y Audio', s.sub, (SELECT clave FROM rubro WHERE orden = 11)
  FROM unnest(string_to_array('Televisor;Parlante;Soporte;Barra de Sonido;Proyector;Radio;Tocadiscos;Equipo de Música', ';')) AS s(sub)
ON CONFLICT (categoria, sub_categoria) DO UPDATE SET rubro = EXCLUDED.rubro;

UPDATE sitio
   SET rubro_por_defecto = (SELECT clave FROM rubro WHERE orden = 11)
 WHERE sitio_key = 'cetrogar';

UPDATE sitio_categoria
   SET categoria = 'Pequeño Electrodoméstico'
 WHERE sitio_key = 'samsung'
   AND origen_id = ANY (ARRAY[166, 258, 259, 260, 261, 262, 263, 264, 274, 275, 276, 277, 278, 279]);

UPDATE productos p
   SET categoria = sc.categoria,
       sub_categoria = CASE WHEN translate(lower(split_part(p.nombre, ' ', 1)), 'áéíóú', 'aeiou')
                                 ~ '^(anaquel|estante|cajon|tapa|filtro|boquilla|deposito|guia)$'
                            THEN 'Accesorio y Repuesto' ELSE '' END,
       rubro = e.clave
  FROM sitio_categoria sc, rubro e
 WHERE e.orden = 11
   AND sc.id = p.sitio_categoria_id
   AND sc.sitio_key = 'samsung'
   AND sc.categoria = 'Pequeño Electrodoméstico'
   AND p.bloqueado_por IS NULL;

UPDATE productos p
   SET rubro = CASE WHEN f.peso_forzado >= e.peso_categoria THEN f.clave ELSE e.clave END
  FROM rubro e
  JOIN categoria_rubro cr ON cr.rubro = e.clave AND cr.sub_categoria = ''
  CROSS JOIN sitio s
  LEFT JOIN rubro f ON f.clave = s.rubro_forzado
 WHERE e.orden = 11
   AND p.categoria = cr.categoria
   AND s.sitio_key = p.sitio_key
   AND p.bloqueado_por IS NULL;

UPDATE productos p
   SET rubro = cr.rubro
  FROM categoria_rubro cr, sitio s, rubro e
 WHERE e.orden = 11
   AND cr.rubro = e.clave
   AND cr.sub_categoria <> ''
   AND p.categoria = cr.categoria
   AND p.sub_categoria = cr.sub_categoria
   AND s.sitio_key = p.sitio_key
   AND COALESCE(s.rubro_forzado, '') = ''
   AND p.bloqueado_por IS NULL;

UPDATE productos p
   SET rubro = s.rubro_por_defecto
  FROM sitio s, rubro e
 WHERE e.orden = 11
   AND s.rubro_por_defecto = e.clave
   AND COALESCE(s.rubro_forzado, '') = ''
   AND s.sitio_key = p.sitio_key
   AND p.bloqueado_por IS NULL
   AND NOT EXISTS (SELECT 1 FROM categoria_rubro cr WHERE cr.categoria = p.categoria AND cr.sub_categoria = '');
