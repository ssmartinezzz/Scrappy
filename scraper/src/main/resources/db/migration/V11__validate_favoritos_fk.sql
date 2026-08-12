-- V11__validate_favoritos_fk.sql — normalize-db-schema-fks-1nf, cierre
--
-- V4 creó `fk_favoritos_url` como NOT VALID a propósito (design D8): la
-- constraint ya valida todo INSERT/UPDATE nuevo y dispara el RESTRICT al borrar
-- el padre, pero el chequeo de backfill sobre el historial quedó diferido
-- porque una instalación vieja con huérfanos no puede quedarse sin bootear por
-- algo que el usuario no puede accionar desde ahí.
--
-- Esto lo cierra sin reintroducir ese riesgo: valida SOLO si no hay huérfanos.
-- Si los hay, deja la constraint como está y avisa. Un VALIDATE incondicional
-- acá sería exactamente la migración que D8 se negó a escribir: la que le
-- rompe el arranque a alguien por datos que la migración no tiene permiso para
-- borrar.

DO $$
DECLARE
    v_huerfanos BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_huerfanos
    FROM favoritos f
    WHERE NOT EXISTS (SELECT 1 FROM productos p WHERE p.url = f.url);

    IF v_huerfanos = 0 THEN
        ALTER TABLE favoritos VALIDATE CONSTRAINT fk_favoritos_url;
        RAISE NOTICE 'fk_favoritos_url validada: cero favoritos huérfanos.';
    ELSE
        RAISE WARNING 'fk_favoritos_url sigue NOT VALID: % favoritos referencian productos que ya no existen. '
                      'La constraint igual se aplica a los inserts nuevos; decidí qué hacer con esas filas '
                      'y corré VALIDATE CONSTRAINT a mano.', v_huerfanos;
    END IF;
END $$;
