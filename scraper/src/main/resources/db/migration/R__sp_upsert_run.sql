-- R__sp_upsert_run.sql — LA definición de sp_upsert_run. Una sola, acá.
--
-- ─── POR QUÉ ESTE ARCHIVO EXISTE ─────────────────────────────────────────
--
-- Postgres no tiene redefinición parcial de función: cambiar una línea del
-- cuerpo obliga a un CREATE OR REPLACE del cuerpo ENTERO. Y una migración
-- Flyway aplicada es byte-frozen, porque Flyway valida checksums. Combinadas,
-- esas dos reglas produjeron **siete copias** de esta misma función de ~90
-- líneas — V1, V3, V5, V7, V17, V21, V22 — cada una idéntica a la anterior
-- salvo una o tres líneas.
--
-- El costo no era el espacio, era que cada copia es una oportunidad de
-- introducir una diferencia que nadie pidió. `StoredProcedureDriftTest` se
-- escribió para atajar exactamente eso: deshace las sustituciones declaradas
-- de cada salto y exige que el resultado sea el cuerpo anterior, carácter por
-- carácter. Funcionó — pero es un test defendiéndonos de una duplicación que
-- no hacía falta tener.
--
-- Una migración REPETIBLE (prefijo `R__`, sin número de versión) es la
-- herramienta que Flyway tiene para esto, y su caso de uso declarado son
-- justamente funciones, vistas y procedimientos. Dos propiedades la hacen
-- correcta acá:
--
--   1. Las repetibles corren DESPUÉS de todas las versionadas. No es
--      convención: `ResolvedMigrationComparator` ordena cualquier migración
--      con versión antes que cualquiera sin versión. Así que este cuerpo es
--      siempre la última palabra, incluso en una instalación desde cero que
--      aplica V1..V22 y crea la función siete veces antes de llegar acá.
--   2. Se re-aplica sola cuando cambia su checksum. Editar este archivo YA es
--      la migración: no hay que escribir una V23 que vuelva a copiar todo.
--
-- ─── QUÉ SIGNIFICA PARA EL PRÓXIMO CAMBIO ────────────────────────────────
--
-- Se edita ACÁ, y nada más. No se agrega una migración versionada nueva para
-- tocar la función. Las siete copias históricas quedan donde están —son
-- inmutables por definición— y valen como registro de cómo llegó hasta acá.
--
-- `StoredProcedureDriftTest` tiene un salto final V22 → R__ con CERO
-- sustituciones declaradas, o sea que exige que este cuerpo sea idéntico al
-- de V22. Eso prueba que introducir este archivo no cambió ni un carácter del
-- comportamiento. Cuando este archivo cambie de verdad, ese salto va a
-- fallar, y ahí el diff de git es la declaración —que es como debería haber
-- sido siempre.

CREATE OR REPLACE FUNCTION sp_upsert_run(p_rows jsonb, p_include_visual boolean)
RETURNS jsonb AS $$
DECLARE
    r              jsonb;
    v_prev_precio  DOUBLE PRECISION;
    v_new_precio   DOUBLE PRECISION;
    v_nuevos       INTEGER := 0;
    v_actualizados INTEGER := 0;
    v_sin_cambios  INTEGER := 0;
    v_fit          TEXT;
    v_estampado    TEXT;
    v_escote       TEXT;
    v_color        TEXT;
BEGIN
    FOR r IN SELECT * FROM jsonb_array_elements(p_rows)
    LOOP
        IF r->>'url' IS NULL OR r->>'url' = '' THEN
            CONTINUE;
        END IF;

        SELECT precio INTO v_prev_precio FROM productos WHERE url = r->>'url' AND activo;
        v_new_precio := (r->>'precio')::DOUBLE PRECISION;

        IF p_include_visual THEN
            v_fit       := COALESCE(r->>'fit', '');
            v_estampado := COALESCE(r->>'estampado', '');
            v_escote    := COALESCE(r->>'escote', '');
            v_color     := COALESCE(r->>'colorDominante', '');
        ELSE
            v_fit := ''; v_estampado := ''; v_escote := ''; v_color := '';
        END IF;

        INSERT INTO productos (
            url, sitio, nombre, precio, precio_orig, imagen_url, categoria, genero,
            ml_score, ml_oferta, ml_tendencia, ml_segment, ml_zscore,
            rubro, marca, gymrat, cantidad_unidades, sub_categoria,
            fit, estampado, escote, color_dominante, activo, touched_at, created_at
        ) VALUES (
            r->>'url', r->>'sitio', r->>'nombre', v_new_precio, (r->>'precioOrig')::DOUBLE PRECISION, r->>'imagenUrl',
            r->>'categoria', r->>'genero',
            COALESCE((r->>'mlScore')::INTEGER, 50), COALESCE((r->>'mlOferta')::boolean, false),
            COALESCE(r->>'mlTendencia', ''), COALESCE(r->>'mlSegment', 'standard'),
            COALESCE((r->>'mlZscore')::DOUBLE PRECISION, 0.0),
            COALESCE(r->>'rubro', 'indumentaria'), nullif(r->>'marca',''),
            COALESCE((r->>'gymrat')::boolean, false),
            COALESCE((r->>'cantidadUnidades')::INTEGER, 1), COALESCE(r->>'subCategoria', ''),
            v_fit, v_estampado, v_escote, v_color, true,
            (r->>'now')::timestamptz, (r->>'now')::timestamptz
        )
        ON CONFLICT (url) DO UPDATE SET
            sitio             = EXCLUDED.sitio,
            nombre            = EXCLUDED.nombre,
            precio            = EXCLUDED.precio,
            precio_orig       = EXCLUDED.precio_orig,
            imagen_url        = EXCLUDED.imagen_url,
            categoria         = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.categoria ELSE productos.categoria END,
            genero            = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.genero ELSE productos.genero END,
            ml_score          = EXCLUDED.ml_score,
            ml_oferta         = EXCLUDED.ml_oferta,
            ml_tendencia      = EXCLUDED.ml_tendencia,
            ml_segment        = EXCLUDED.ml_segment,
            ml_zscore         = EXCLUDED.ml_zscore,
            rubro             = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.rubro ELSE productos.rubro END,
            marca             = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.marca ELSE productos.marca END,
            gymrat            = EXCLUDED.gymrat,
            cantidad_unidades = EXCLUDED.cantidad_unidades,
            sub_categoria     = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.sub_categoria ELSE productos.sub_categoria END,
            fit               = COALESCE(NULLIF(EXCLUDED.fit, ''), productos.fit),
            estampado         = COALESCE(NULLIF(EXCLUDED.estampado, ''), productos.estampado),
            escote            = COALESCE(NULLIF(EXCLUDED.escote, ''), productos.escote),
            color_dominante   = COALESCE(NULLIF(EXCLUDED.color_dominante, ''), productos.color_dominante),
            activo            = true,
            touched_at        = EXCLUDED.touched_at;

        DELETE FROM producto_talle WHERE url = r->>'url';
        INSERT INTO producto_talle (url, posicion, talle)
        SELECT r->>'url', t.ord::smallint, t.val
        FROM jsonb_array_elements_text(COALESCE(r->'talles', '[]'::jsonb)) WITH ORDINALITY AS t(val, ord)
        WHERE t.val <> '';

        DELETE FROM producto_badge WHERE url = r->>'url';
        INSERT INTO producto_badge (url, posicion, badge)
        SELECT r->>'url', b.ord::smallint, b.val
        FROM jsonb_array_elements_text(COALESCE(r->'mlBadges', '[]'::jsonb)) WITH ORDINALITY AS b(val, ord)
        WHERE b.val <> '';

        IF v_prev_precio IS NULL THEN
            v_nuevos := v_nuevos + 1;
            INSERT INTO precio_historico (url, precio, fecha)
            VALUES (r->>'url', v_new_precio, (r->>'fecha')::date)
            ON CONFLICT (url, fecha) DO NOTHING;
        ELSIF abs(v_prev_precio - v_new_precio) > 0.01 THEN
            v_actualizados := v_actualizados + 1;
            INSERT INTO precio_historico (url, precio, fecha)
            VALUES (r->>'url', v_new_precio, (r->>'fecha')::date)
            ON CONFLICT (url, fecha) DO NOTHING;
        ELSE
            v_sin_cambios := v_sin_cambios + 1;
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'nuevos', v_nuevos,
        'actualizados', v_actualizados,
        'sinCambios', v_sin_cambios
    );
END;
$$ LANGUAGE plpgsql;
