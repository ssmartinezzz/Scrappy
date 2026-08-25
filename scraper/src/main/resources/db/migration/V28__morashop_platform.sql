-- V28__morashop_platform.sql — add-morashop-and-fix-entreno-pagination
--
-- Un valor de plataforma entra al dominio cerrado y una fila de seed con él.
-- Sigue CHECK y no tabla de lookup por el mismo criterio de V6/V18/V24: es un
-- dominio chico (12 valores antes de esto). La inversión de V13 —81 valores,
-- pasa a tabla— sigue sin aplicar.
--
-- POR QUÉ V28, Y QUÉ PASÓ AL MERGEAR.
-- Master tenía V25 como última migración cuando se escribió esto. V26 estaba
-- tomada por `feature/user-accounts-and-roles` y V27 por `feat/inpro-oficina`,
-- que agrega `inpro` a ESTE MISMO CHECK. El orden acordado con las dos ramas
-- fue V26 -> V27 -> V28, y no era preferencia: `application.properties` no
-- setea `spring.flyway.out-of-order`, así que es false, y con
-- `validateOnMigrate` en true una versión menor que llega después de una mayor
-- se rechaza.
--
-- Las dos aterrizaron primero, así que la lista de abajo YA ESTÁ REBASEADA:
-- son los doce de V27 (que incluyen `inpro`) más `morashop` = trece. Eso es
-- exactamente lo que este header pedía cuando aún no lo estaba: **rebasear la
-- lista del CHECK sobre el dominio mergeado, NUNCA renumerar.** Renumerar no
-- arregla nada porque todas estas migraciones hacen DROP + ADD del dominio
-- COMPLETO: la colisión es de contenido, no de número.
--
-- Para la próxima que agregue un valor acá, el mismo trato, y tres cosas que
-- esta rama aprendió a los golpes: (1) si no rebaseás, Postgres valida las
-- filas existentes al agregar el CHECK y el ADD es rechazado — en cada base
-- nueva, cada CI y cada Testcontainers, no sólo en un deploy; (2) los
-- rollbacks componen al revés, así que tu bloque nuevo rompe el test de
-- rollback del anterior y hay que ejecutarlos de más nuevo a más viejo;
-- (3) `config.properties` auto-mergea limpio y no avisa nada — después de
-- mergear tiene que haber 27 sitios configurados (25 en el master de entonces
-- + inpro + morashop).
--
-- SÓLO SE TOCA `sitio_plataforma_check`. V27 además re-lista
-- `chk_productos_rubro_domain` y `sitio_rubro_forzado_check` para sumarles
-- 'oficina'; acá no se tocan, porque 'suplementos' ya es válido en los dos.
-- Re-listar cualquiera de ellos acá les borraría el 'oficina' que agregó V27.

-- ── 1. `sitio.plataforma`: 12 -> 13 valores ─────────────────────────────────
-- Morashop es un Tiendanube genuino y el extractor compartido lee sus cards
-- sin cambios. El valor propio existe porque desde V20 ScraperFactory rutea
-- EXCLUSIVAMENTE por `sitio.plataforma` vía SiteRegistry y los name-sets en
-- código se borraron (CODE-6). Morashop necesita su propia page —descubre las
-- categorías hoja porque no tiene URL de catálogo—, y rutear por clave de
-- sitio reintroduciría justo lo que V20 sacó. Monkyforce sentó el precedente:
-- también es Tiendanube, también especializa un solo seam, también tiene valor
-- propio.
ALTER TABLE sitio DROP CONSTRAINT sitio_plataforma_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_plataforma_check
    CHECK (plataforma IN ('tiendanube','shopify','vtex','vaypol','woocommerce',
                          'monkyforce','maximus','fullh4rd','compragamer',
                          'qloud','oscommerce','inpro','morashop'));

-- ── 2. Seed ─────────────────────────────────────────────────────────────────
-- rubro_forzado='suplementos' igual que Entreno, el único otro sitio del rubro.
-- origen='config' porque tiene entrada en config.properties, que es lo que
-- SitioSeedSyncTest exige. ON CONFLICT DO NOTHING: misma postura que todo
-- INSERT de seed de esta tabla, un re-run no puede pisar una fila que un
-- commit posterior o el dashboard ya tocaron.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Morashop', 'morashop', 'morashop', false, 'suplementos', 'config')
ON CONFLICT (nombre) DO NOTHING;
