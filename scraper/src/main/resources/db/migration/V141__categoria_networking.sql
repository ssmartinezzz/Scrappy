-- V141__categoria_networking.sql
--
-- Category 'Networking' replaces 'Red' (tecnologia, same rubro and price index), and the network cables, RJ45 plugs and to-Ethernet adapters
-- that sat in 'Cable' move to it. 'Cable' stays a category (USB, video, audio, power) and loses its 'red' sub.
-- A new category is TWO changes: the classifier produces it, and this row lets productos.categoria (fk_productos_categoria) accept it.
-- The references move in FK order: the new categoria row and its categoria_rubro row first, then productos, sitio_categoria,
-- categoria_stats, categoria_dismiss and saved_outfit_item (the last two have no FK), then the old rows are deleted.
--
-- Sub-category (SubcategoryResolver -> NetworkingReglas.SUBS): the first rule that matches wins, '' when none does. Router, Mesh, Access Point,
-- Switch, Repetidor, Placa y Adaptador, Cable de Red, Conector; the stored values are the chip labels. Mesh beats Router and Access Point;
-- Access Point beats Router. The regexes are the same text as NetworkingReglas over the same tokens (the name lower-cased, accent-folded,
-- every run of non [a-z0-9] one space, space-padded: "Cat.5e" is ' cat 5e '); the keywords of GarmentTaxonomy.KW_RED are read over the
-- name with its punctuation kept, as the classifier reads them. V141NetworkingAgreementTest runs this SQL and the Java over real names.
--
-- Rows:
--   'Red'    every one becomes 'Networking' (a rename), active or inactive, with the new sub. A row locked by hand (bloqueado_por) is renamed
--            too, because the old row cannot stay referenced, and keeps its choice: the old lowercase sub maps to its new label.
--   'Cable'  a row the classifier now sends to Networking (a KW_RED keyword, a mesh or extender, or a cable / plug / adapter that leads the
--            name and says RJ45, UTP, patch cord, Cat5-8 or Ethernet, unless it names HDMI, VGA, telephone or audio or is a multiport hub) moves
--            with its new sub. A multiport hub or dock that lists an RJ45 stays 'Cable' and, if its sub was 'red', takes the sub the
--            remaining Cable rules give (video, usb, poder) or ''. A row locked by hand is not touched.
-- The rubro does not change. Re-runnable: a second run finds no 'Red' and no network cable left in 'Cable'. The V40 trigger bumps
-- catalog_version once at COMMIT however many rows change. No ALTER TABLE on productos, so the SET CONSTRAINTS flush of V112 is not needed.
-- The statements live in one block so the two names and the regexes are written once (Sonar S1192).
DO $v141$
DECLARE
    nuevo        CONSTANT text := 'Networking';
    viejo        CONSTANT text := 'Red';
    cable        CONSTANT text := 'Cable';
    kw_red       CONSTANT text := 'router|modem|repetidor|access point|placa de red|extensor de rango|adaptador wifi|adaptador usb wifi'
                                  || '|adaptador de red|adaptador bluetooth|antena wifi|powerline|placa wifi';
    kw_contexto  CONSTANT text := 'gigabit|puerto|poe|ethernet|10/100|rj45|rj-45|omada';
    nombra       CONSTANT text := '^(?=.* mesh )(?=.* (?:wi ?fi|deco|router|tp link|tplink|access point) )'
                                  || '| (?:extensor|extensores) (?:de )?(?:wi ?fi|red|senal|rango) ';
    lider        CONSTANT text := '^ (?:cable|cables|adaptador|adaptadores|ficha|patchcord|conversor|prolongador|extension usb'
                                  || '|conector|conectores|plug|plugs|jack|jacks|keystone|modulo keystone|acoplador|acopladores) ';
    senal        CONSTANT text := ' (?:cable de red|cables de red|patch cord|patchcord|patchcords|patch cable|utp|ftp|sftp|sstp'
                                  || '|rj ?45|cat ?[5-8][ae]?|categoria [5-8][ae]?|ethernet|lan|keystone) ';
    no_es_de_red CONSTANT text := ' (?:hdmi|displayport|display port|vga|dvi|rj ?11|rj ?9|coaxial|audio|rca) ';
    concentrador CONSTANT text := ' (?:[0-9]+ en 1|[0-9]+ in 1|multipuerto|multiport|dock|docking) |^ (?:adaptador )?hub ';
BEGIN
    INSERT INTO categoria (nombre, indice)
    SELECT nuevo, c.indice FROM categoria c WHERE c.nombre = viejo
    ON CONFLICT (nombre) DO NOTHING;

    INSERT INTO categoria_rubro (categoria, sub_categoria, rubro, pisa_forzado)
    SELECT nuevo, cr.sub_categoria, cr.rubro, cr.pisa_forzado FROM categoria_rubro cr WHERE cr.categoria = viejo
    ON CONFLICT (categoria, sub_categoria) DO NOTHING;

    WITH rx (ord, sub, re) AS (VALUES
      (1, 'Conector', '^ (?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*(?:ficha|fichas|conector|conectores|plug|plugs|jack|jacks|keystone'
                      || '|modulo keystone|acoplador|acopladores) '),
      (2, 'Cable de Red', '^(?!.* (?:usb|type c|thunderbolt)(?: [a-z0-9]+){0,4} (?:a|to) (?:gigabit |giga )?(?:ethernet|rj ?45|lan|red) )'
                          || ' (?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*(?:cable|cables|patch cord|patchcord|patchcords|utp) '),
      (3, 'Placa y Adaptador', '^ (?:(?:pack|kit|combo|set|x|de|outlet|[0-9]+) )*(?:placa|placas|adaptador|adaptadores|tarjeta de red) '),
      (4, 'Mesh', ' (?:deco|velop|eero|orbi) |^(?:(?! (?:one|easy) mesh ).)* mesh '),
      (5, 'Access Point', ' (?:access point|accesspoint|ap[0-9]*|cpe[0-9]*|eap[0-9]+[a-z]*|nanostation|nanobeam|powerbeam|power beam) '),
      (6, 'Repetidor', ' (?:repetidor|repetidores|extensor|extensores|range extender|wifi extender|wi fi extender) '),
      (7, 'Router', ' (?:router|routers|modem|modems|xpon|gpon) '),
      (8, 'Switch', '^(?!.* hub ).* (?:switch|switches|fortiswitch) '),
      (9, 'Placa y Adaptador', ' (?:placa|placas|adaptador|adaptadores|adapter|dongle|receptor|nic|tarjeta de red) ')
    ), base AS (
      SELECT p.url, p.categoria, p.sub_categoria, p.bloqueado_por,
             ' ' || lower(translate(p.nombre, 'áàäéèëíìïóòöúùüñÁÀÄÉÈËÍÌÏÓÒÖÚÙÜÑ', 'aaaeeeiiiooouuunAAAEEEIIIOOOUUUN')) || ' ' AS n
        FROM productos p
       WHERE p.categoria IN (viejo, cable)
    ), tok AS (
      SELECT b.*, ' ' || btrim(regexp_replace(b.n, '[^a-z0-9]+', ' ', 'g')) || ' ' AS t
        FROM base b
    ), cls AS (
      SELECT k.*,
             (k.n ~ kw_red OR (k.n ~ ' switch ' AND k.n ~ kw_contexto) OR k.t ~ nombra
              OR (k.n ~ lider AND k.t ~ senal AND k.t !~ no_es_de_red AND k.t !~ concentrador)) AS es_red
        FROM tok k
    ), dest AS (
      SELECT c.url,
             (c.categoria = viejo OR (c.bloqueado_por IS NULL AND c.es_red)) AS mueve,
             CASE WHEN c.bloqueado_por IS NULL
                  THEN COALESCE((SELECT r.sub FROM rx r WHERE c.t ~ r.re ORDER BY r.ord LIMIT 1), '')
                  ELSE COALESCE((SELECT r.sub FROM rx r JOIN (VALUES ('router', 7), ('switch', 8), ('repetidor', 6), ('adaptador', 3)) m (vieja, ord)
                                                           ON m.ord = r.ord
                                  WHERE m.vieja = c.sub_categoria), c.sub_categoria) END AS sub_red,
             CASE WHEN c.n ~ ' (?:hdmi|displayport|display port|vga|dvi) ' THEN 'video'
                  WHEN c.n ~ ' usb ' THEN 'usb'
                  WHEN c.n ~ ' (?:power|alimentacion|poder) ' THEN 'poder'
                  ELSE '' END AS sub_cable
        FROM cls c
    )
    UPDATE productos p
       SET categoria = CASE WHEN d.mueve THEN nuevo ELSE p.categoria END,
           sub_categoria = CASE WHEN d.mueve THEN d.sub_red ELSE d.sub_cable END
      FROM dest d
     WHERE p.url = d.url
       AND (d.mueve OR (p.categoria = cable AND p.sub_categoria = 'red' AND p.bloqueado_por IS NULL))
       AND (p.categoria IS DISTINCT FROM CASE WHEN d.mueve THEN nuevo ELSE p.categoria END
            OR p.sub_categoria IS DISTINCT FROM CASE WHEN d.mueve THEN d.sub_red ELSE d.sub_cable END);

    UPDATE sitio_categoria SET categoria = nuevo WHERE categoria = viejo;

    DELETE FROM categoria_stats s
     WHERE s.categoria = viejo AND EXISTS (SELECT 1 FROM categoria_stats n WHERE n.categoria = nuevo);
    UPDATE categoria_stats SET categoria = nuevo WHERE categoria = viejo;

    UPDATE categoria_dismiss SET categoria = nuevo WHERE categoria = viejo;
    UPDATE saved_outfit_item SET categoria = nuevo WHERE categoria = viejo;

    DELETE FROM categoria_rubro WHERE categoria = viejo;
    DELETE FROM categoria WHERE nombre = viejo;
END
$v141$;
