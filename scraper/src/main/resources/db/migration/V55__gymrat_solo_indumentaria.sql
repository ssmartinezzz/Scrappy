-- V55__gymrat_solo_indumentaria.sql
--
-- GymratTagger had no rubro guard: KW_TRAINING_ROPA ("performance") and GYM_MARCAS tagged
-- gabinetes, monitores and pasta térmica as gymrat (40 rows on dev, 34 active, 2026-10-27).
-- The tagger now refuses any rubro other than indumentaria; this aligns the rows already stored
-- instead of waiting for every affected site to be scraped again. NULL rubro means indumentaria,
-- same as the tagger, and `<>` already leaves it out. One statement: atomic, and idempotent
-- because it only touches rows still flagged.
UPDATE productos
   SET gymrat = false
 WHERE gymrat
   AND rubro <> 'indumentaria';
