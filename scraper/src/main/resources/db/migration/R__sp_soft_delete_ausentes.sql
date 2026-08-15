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

-- `p_sitios` acota el barrido a los sitios que el run efectivamente cubrió.
-- Sin él la condición era global (`WHERE activo AND NOT (url = ANY(p_urls))`),
-- así que un run de un subconjunto de sitios daba por desaparecido a TODO el
-- resto del catálogo. Medido sobre la base real después de un run de solo
-- tecnología (2026-08-15 23:09:52): 5806 productos de 19 sitios que ni siquiera
-- se visitaron pasaron a activo=false en una sola sentencia — Sporting de 1860
-- a 0, y el catálogo entero de indumentaria y suplementos con él.
--
-- "Ausente" sólo significa algo dentro de un sitio que se miró. Para un sitio
-- que no se miró no hay evidencia de nada, y `activo=false` es una afirmación,
-- no la ausencia de una.
--
-- El alcance lo deriva el llamador de los productos del batch, no de la lista
-- de sitios pedidos: un sitio pedido cuyo scraper se rompe llega con 0
-- productos, y tratar eso como "desapareció todo" es justo el modo de falla que
-- `SiteYieldGuard` existe para detectar. Un array vacío no borra nada.
--
-- La firma cambia de 2 a 3 argumentos, así que hay que DROPear la vieja: un
-- CREATE OR REPLACE con otra aridad crea una SOBRECARGA, no un reemplazo, y
-- dejaría la versión global viva y llamable.
DROP FUNCTION IF EXISTS sp_soft_delete_ausentes(text[], text);

CREATE OR REPLACE FUNCTION sp_soft_delete_ausentes(p_urls text[], p_now text, p_sitios text[])
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    UPDATE productos
    SET activo = false, touched_at = p_now::timestamptz
    WHERE activo AND sitio = ANY(p_sitios) AND NOT (url = ANY(p_urls));
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;
