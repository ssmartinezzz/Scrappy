-- V20__sitio_owns_platform.sql — close-1nf-and-3nf-foundation (3NF extension)
-- (design E1)
--
-- sitio.plataforma becomes the ONLY copy. V18 seeded it from
-- sitios_dinamicos at that migration's instant but left both columns
-- alive — read by nothing, on purpose (DD3). This migration re-syncs any
-- drift a POST /api/sitios call could have introduced between V18 and now
-- (a dashboard-added site whose plataforma changed after being absorbed),
-- then drops sitios_dinamicos.plataforma — keeping it around would be the
-- sixth taxonomy copy this table exists to close (CODE-6). sitios_dinamicos
-- keeps (nombre, url, created_at): its remaining job is "the URL to scrape".
--
-- rubro_forzado backfill: measured 0 rows (apply-time re-run of design E3's
-- query, N=23 sitio rows — all origen='config', no dinamico/historico rows
-- exist on this DB to exercise the forward risk against). Every sitio_key
-- that could differ under the substring->equality change (compragamer,
-- fullh4rd, maximus, entreno + their domain-suffixed variants, unreachable
-- today since sitioKey strips dots) equals its own token exactly. No UPDATE
-- ships here — an empty one would look like it did something it didn't.

UPDATE sitio s
SET plataforma = d.plataforma
FROM sitios_dinamicos d
WHERE s.nombre = d.nombre
  AND d.plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                        'monkyforce','maximus','fullh4rd','compragamer');

ALTER TABLE sitios_dinamicos DROP COLUMN plataforma;
