-- V28__morashop_platform.sql — add-morashop-and-fix-entreno-pagination
--
-- Un valor de plataforma entra al dominio cerrado y una fila de seed con él.
-- Sigue CHECK y no tabla de lookup por el mismo criterio de V6/V18/V24: es un
-- dominio chico (11 valores antes de esto). La inversión de V13 —81 valores,
-- pasa a tabla— sigue sin aplicar.
--
-- POR QUÉ V28, Y QUÉ TIENE QUE HACER QUIEN MERGEE DESPUÉS.
-- Master tiene V25 como última migración. V26 está tomada por la rama
-- `feature/user-accounts-and-roles` (usuario/rol/refresh_token) y V27 por
-- `feat/inpro-oficina` (PR #151), que agrega `inpro` a ESTE MISMO CHECK.
-- Ninguna de las dos está mergeada al escribir esto. El orden acordado con
-- las dos ramas es V26 → V27 → V28, y no es preferencia: `application.properties`
-- no setea `spring.flyway.out-of-order`, así que es false, y con
-- `validateOnMigrate` en true una versión menor que llega después de una mayor
-- se rechaza.
--
-- INSTRUCCIÓN CONCRETA, no advertencia genérica: **quien aterrice segundo
-- rebasea la lista del CHECK sobre el dominio ya mergeado, NO renumera.**
-- Renumerar no arregla nada porque todas estas migraciones hacen DROP + ADD
-- del dominio COMPLETO: la colisión es de contenido, no de número. Cuando V27
-- esté en master, la lista de acá abajo pasa a ser sus doce valores + 'morashop'
-- = trece, y el bloque `rollback:V28` de docs/DATABASE.md se rebasea igual.
--
-- Si no se rebasea, falla RUIDOSO en tres lugares independientes, y por eso no
-- hace falta maquinaria extra: (1) Postgres valida las filas existentes al
-- agregar un CHECK y el INSERT de V27 garantiza que la fila `inpro` exista, así
-- que el ADD es rechazado y Flyway aborta —en cada base nueva, cada CI y cada
-- Testcontainers, no sólo en un deploy—; (2) las dos ramas editan
-- SitiosRepository.PLATAFORMAS_VALIDAS y el puntero de PlatformVocabularySyncTest,
-- así que git conflictúa; (3) ese test compara el CHECK contra la copia Java y
-- pone la build en rojo. El archivo que NO avisa es config.properties, que
-- auto-mergea limpio: después de mergear las dos ramas tiene que haber 27
-- sitios configurados (25 en master + inpro + morashop).
--
-- SÓLO SE TOCA `sitio_plataforma_check`. V27 además re-lista
-- `chk_productos_rubro_domain` y `sitio_rubro_forzado_check` para sumarles
-- 'oficina'; acá no se tocan, porque 'suplementos' ya es válido en los dos.
-- Re-listar cualquiera de ellos desde este baseline les borraría 'oficina'.

-- ── 1. `sitio.plataforma`: 11 -> 12 valores ─────────────────────────────────
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
                          'qloud','oscommerce','morashop'));

-- ── 2. Seed ─────────────────────────────────────────────────────────────────
-- rubro_forzado='suplementos' igual que Entreno, el único otro sitio del rubro.
-- origen='config' porque tiene entrada en config.properties, que es lo que
-- SitioSeedSyncTest exige. ON CONFLICT DO NOTHING: misma postura que todo
-- INSERT de seed de esta tabla, un re-run no puede pisar una fila que un
-- commit posterior o el dashboard ya tocaron.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Morashop', 'morashop', 'morashop', false, 'suplementos', 'config')
ON CONFLICT (nombre) DO NOTHING;
