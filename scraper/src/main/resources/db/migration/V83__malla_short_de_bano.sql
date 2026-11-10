-- V83__malla_short_de_bano.sql
--
-- IndumentariaClassifier now files swim shorts and trunks as Malla: "Swim Short Walker Black" (Harvey, 10
-- active rows on dev, 2026-11-10) came out as Short because "short" is a leading noun, and "Trajes de Baño
-- adidas BÓXERS SOLID SWIM" as Calzoncillos because of "boxer". This aligns the rows already stored instead
-- of waiting for every site to be scraped again. The next scrape is the source of truth.
-- Rule (identical to GarmentTaxonomy.KW_MALLA_SHORT_BANO + KW_MALLA_VETO): accent-stripped lower-cased name
-- containing swim short, swim trunk, short(s)/boxer(s)/bermuda(s) de bano or trajes de bano, and none of the
-- measure words that mark Easy's mesh ( mts, cm , mm , m2, lts, kg , grs). Only the categories the
-- classifier used to produce for these (Short, Calzoncillos, Bermuda). Never a row locked by hand (V3).
-- Idempotent: a handled row is already 'Malla' and no longer matches its WHERE.
UPDATE productos
   SET categoria = 'Malla'
 WHERE bloqueado_por IS NULL
   AND categoria IN ('Short', 'Calzoncillos', 'Bermuda')
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       ~ '(swim short|swim trunk|short de bano|shorts de bano|boxer de bano|boxers de bano|bermuda de bano|bermudas de bano|trajes de bano)'
   AND (' ' || lower(translate(nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
       !~ '( mts| cm | mm | m2| lts| kg | grs)';
