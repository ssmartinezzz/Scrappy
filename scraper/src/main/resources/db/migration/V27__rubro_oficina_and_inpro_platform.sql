-- V27__rubro_oficina_and_inpro_platform.sql — add-inpro-office-store
--
-- Tres dominios cerrados se amplían acá y una fila de seed entra. Los tres
-- son CHECK y no tabla de lookup por el mismo criterio de V6/V18/V24: son
-- dominios chicos (3, 2 y 11 valores antes de esto). La inversión de V13
-- —81 valores, pasa a tabla— sigue sin aplicar.
--
-- POR QUÉ V27 Y NO V26. La rama `feature/user-accounts-and-roles`, abierta en
-- paralelo, ya tiene su propia V26 (usuario/rol/refresh_token). Este cambio
-- sale de master, donde la última migración es V25, así que V26 queda
-- RESERVADA para esa rama y este archivo toma V27. El hueco es inocuo —
-- Flyway aplica en orden de versión y tolera faltantes— pero el orden de
-- merge NO lo es: si esta rama entra primero, la V26 de la otra queda
-- out-of-order y `validateOnMigrate` la rechaza. Mergear ESTA rama después
-- de user-accounts-and-roles, o renumerar.
--
-- Los nombres de los tres constraints se confirmaron contra un Postgres real
-- antes de escribir esto (`pg_constraint`), no se asumieron: `V6` nombra el
-- suyo explícitamente (`chk_productos_rubro_domain`), y los dos CHECK inline
-- y sin nombre de `V18` los auto-nombró Postgres `sitio_plataforma_check` y
-- `sitio_rubro_forzado_check`. Se mantienen esos nombres — renombrarlos haría
-- que el rollback documentado en docs/DATABASE.md dejara de ser literal.

-- ── 1. `productos.rubro`: 3 -> 4 valores ────────────────────────────────────
-- Una silla ergonómica no es indumentaria, ni tecnología, ni suplemento.
-- Meterla a la fuerza en uno de los tres haría exactamente lo que `V6` vino a
-- impedir: un valor que miente sobre el producto. Ver docs/DATABASE.md.
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos', 'oficina'));

-- ── 2. `sitio.rubro_forzado`: 2 -> 3 valores ────────────────────────────────
-- Sin esto la fila de seed de abajo no entra: RubroResolver lee rubro_forzado
-- para resolver el rubro de todo producto de un sitio de rubro único, igual
-- que hace con 'tecnologia' desde V18.
ALTER TABLE sitio DROP CONSTRAINT sitio_rubro_forzado_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_rubro_forzado_check
    CHECK (rubro_forzado IN ('tecnologia','suplementos','oficina'));

-- ── 3. `sitio.plataforma`: 11 -> 12 valores ─────────────────────────────────
-- INPRO es un Tiendanube HEADLESS: los datos que sirve son los objetos crudos
-- de la API de Tiendanube (variants/compare_at_price/stock/sku), pero la
-- vidriera es un Next.js propio en Vercel y el storefront clásico no es
-- alcanzable — `inpro.mitiendanube.com` redirige a OTRA tienda
-- (inproindumentaria.com.ar) y los slugs candidatos dan 410. Sembrarlo como
-- 'tiendanube' lo rutearía a TiendanubeScraper, que scrapea un DOM que en
-- inpro.ar no existe: 0 productos en silencio, el mismo bug que V24 cerró.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro'));

-- ── 4. Seed ─────────────────────────────────────────────────────────────────
-- origen='config' porque tiene su entrada en config.properties, que es lo que
-- SitioSeedSyncTest exige. ON CONFLICT DO NOTHING: misma postura que todo
-- INSERT de seed de esta tabla, un re-run no puede pisar una fila que un
-- commit posterior o el dashboard ya tocaron.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Inpro', 'inpro', 'inpro', false, 'oficina', 'config')
ON CONFLICT (nombre) DO NOTHING;
