-- R__sp_soft_delete_ausentes.sql — LA definición de sp_soft_delete_ausentes.
--
-- Mismo criterio que `R__sp_upsert_run.sql`, que explica el razonamiento
-- completo: Postgres no tiene redefinición parcial de función y una migración
-- Flyway aplicada es byte-frozen, así que cada cambio de una línea obligaba a
-- copiar el cuerpo entero a una migración nueva. Esta función acumuló dos
-- copias (V1 y V5) — menos que las siete de `sp_upsert_run`, pero por el mismo
-- mecanismo y con el mismo final si se la dejaba así.
--
-- Se mueve junto con la otra a propósito: dejar una función bajo el régimen
-- viejo y otra bajo el nuevo es peor que cualquiera de los dos regímenes,
-- porque obliga a recordar cuál es cuál antes de tocar nada.
--
-- Se edita ACÁ. Flyway la re-aplica sola cuando cambia el checksum, y corre
-- después de todas las versionadas.
--
-- `StoredProcedureDriftTest` tiene un salto V5 → R__ con CERO sustituciones
-- declaradas: exige que este cuerpo sea idéntico al de V5, o sea que mover la
-- definición acá no cambió nada.

CREATE OR REPLACE FUNCTION sp_soft_delete_ausentes(p_urls text[], p_now text)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    UPDATE productos
    SET activo = false, touched_at = p_now::timestamptz
    WHERE activo AND NOT (url = ANY(p_urls));
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;
