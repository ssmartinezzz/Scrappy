-- V19__abstain_site_name_brands.sql — close-1nf-and-3nf-foundation
-- (spec "catalog-brand-vocabulary", design DD8)
--
-- BrandExtractor.extraer no longer falls back to the site name when no
-- curated brand matches (D3) — it abstains to "" (CODE-5), same as the rest
-- of the project's fill-only fields. This backfills the existing rows where
-- marca still equals the site name from that old fallback.
--
-- NOT `WHERE marca = sitio` alone: three canonical brands ARE also site
-- names (Bulks, Fuark, Harvey — see BrandExtractor.MARCAS and
-- MarcasSiteIntersectionTest, which asserts this exception list is exactly
-- and only these three, bidirectionally). A Bulks product legitimately
-- branded "Bulks" is real brand data matched by \b, not the fallback bug —
-- blind clearing would destroy it. The miss on those three stores is
-- conservative in the safe direction: a handful of genuine leftover
-- site-name `marca` values converge on the next scrape; nothing real is
-- ever destroyed by this statement.
--
-- One-way: no rollback block. The old rule was literally `marca = sitio`, so
-- there is nothing to reconstruct — re-deriving it means re-scraping.

UPDATE productos
SET marca = ''
WHERE marca = sitio
  AND marca NOT IN ('Bulks', 'Fuark', 'Harvey');
