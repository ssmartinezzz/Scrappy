-- V24__platform_vocabulary_qloud_oscommerce.sql — fix-zero-yield-tech-sites
-- (spec "site-platform-vocabulary"/Platform Domain Extension Via New
--  Versioned Migrations Only, design D4)
--
-- Rockethard and Venex scraped 0 products because they were never
-- registered at all: no config.properties entry, no `sitio` seed row (V18's
-- 23-row seed predates both). `sitio.plataforma`'s CHECK is a closed domain
-- (V18), so a platform this project didn't already support needs a new
-- versioned migration widening it — V18 is byte-frozen and must not be
-- touched.
--
-- Confirmed against the live schema (`information_schema`/`pg_constraint`)
-- before writing this: the auto-generated constraint name from V18's inline,
-- unnamed CHECK is exactly `sitio_plataforma_check`. Kept as-is — renaming it
-- would make the rollback below non-literal.
--
-- Two platforms, not the three the original exploration considered: Logg's
-- hydration source was never identified (see docs/ARCHITECTURE.md, "Bloqueo
-- conocido: Logg queda fuera de fix-zero-yield-tech-sites"), so no `logg`
-- value ever enters this domain and there is no V25 in this change.

ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce'));

-- Rockethard (Qloud platform, hardware/tech) and Venex (osCommerce, hardware/
-- tech) — both rubro_forzado='tecnologia' per the spec's "rubro_forzado
-- Seeded for the New Tech Sites" requirement. ON CONFLICT DO NOTHING: same
-- posture as every other seed insert in this table, a re-run must not clobber
-- a row a later commit or dashboard edit already touched.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Rockethard', 'rockethard', 'qloud',      false, 'tecnologia', 'config'),
    ('Venex',      'venex',      'oscommerce', false, 'tecnologia', 'config')
ON CONFLICT (nombre) DO NOTHING;
