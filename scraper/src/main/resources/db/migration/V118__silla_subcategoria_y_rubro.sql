-- V118__silla_subcategoria_y_rubro.sql
--
-- Chairs get a sub_categoria (gamer, oficina, infantil, exterior, comedor) and, where the sub says it is a home product,
-- the hogar rubro. Silla is an office category (CategoryGroups.categoriasOficina), so all 960 active chairs of the dev
-- catalogue were 'oficina' (874) or 'tecnologia' (86, the sites that force it): a dining, garden or baby chair read as
-- office furniture. The resolver is SubcategoryResolver (the "Silla" tier-1 entry) plus CategoryGroups.rubroDeSubcategoria
-- consulted by RubroResolver; this is the same rule over the stored rows.
--
-- One single-line text blob (prioridad|sub|palabras rows joined by ';', the words by ','; no raw newline: Sonar flags one
-- inside a literal), first match wins in prioridad order. A word matches as ' word ' in ' name ' with the accents of
-- AccentStripper removed and the punctuation kept, exactly like the Java side ("comedor/jardin" is one token).
-- Rubro: a site with a forced rubro keeps its rows' rubro (the forced branches of RubroResolver return first); on every
-- other site a comedor/exterior/infantil chair is hogar and any other chair stays oficina. A row with no sub gets '' and
-- keeps today's rubro. Locked rows (bloqueado_por) are not touched, as in V103. Active and inactive rows alike.
-- SET CONSTRAINTS flushes the deferred trigger of V40 before the UPDATE (productos is altered in the same transaction).
SET CONSTRAINTS ALL IMMEDIATE;

WITH linea(l) AS (SELECT regexp_split_to_table('1|gamer|gamer,gamers,gaming,throne;2|oficina|oficina,ergonomica,ergonomico,ejecutiva,ejecutivo,escritorio,operativa,operativo,giratoria,presidente,gerencial,mesh,neumatica,secretarial,pc;3|infantil|bebe,bebes,bebesit,infantil,infantiles,infanti,nino,ninos,nina,ninas,montessori,comer,maxi cosi,high chair,booster;4|exterior|jardin,exterior,outdoor,outdoors,playa,camping,reposera,director,pesca,pescador,rattan,ratan,textileno,outzen,keter;5|comedor|comedor,cocina,eames,eams,tulip,banqueta,banquetas,banquito,taburete,taburetes,stool,bar,mecedora,desayunadora,barra,comedor/jardin;6|exterior|plegable,plegables', ';')),
campo(f) AS (SELECT string_to_array(l, '|') FROM linea),
regla AS (SELECT f[1]::int AS prioridad, f[2] AS sub, string_to_array(f[3], ',') AS palabras FROM campo),
silla AS (
  SELECT p.url, COALESCE(s.rubro_forzado, '') AS forzado,
         ' ' || translate(lower(p.nombre), 'áàäéèëíìïóòöúùüñ', 'aaaeeeiiiooouuun') || ' ' AS n
    FROM productos p
    LEFT JOIN sitio s ON s.sitio_key = p.sitio_key
   WHERE p.categoria = 'Silla' AND p.bloqueado_por IS NULL
),
nuevo AS (
  SELECT si.url, si.forzado,
         COALESCE((SELECT r.sub FROM regla r
                    WHERE EXISTS (SELECT 1 FROM unnest(r.palabras) w WHERE position(' ' || w || ' ' IN si.n) > 0)
                    ORDER BY r.prioridad LIMIT 1), '') AS sub
    FROM silla si
)
UPDATE productos p
   SET sub_categoria = nuevo.sub,
       rubro = CASE WHEN nuevo.forzado <> '' THEN p.rubro
                    WHEN nuevo.sub ~ '^(?:comedor|exterior|infantil)$' THEN 'hogar'
                    ELSE 'oficina' END
  FROM nuevo
 WHERE p.url = nuevo.url;
