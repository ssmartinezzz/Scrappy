-- V22__drop_marca_premium.sql — close-1nf-and-3nf-foundation, design E2
--
-- `productos.marca_premium` is a transitive dependency of `sitio`, not of the
-- primary key. Despite the column name it was never derived from the brand:
-- `NormalizerService` computed it as `SITIOS_PREMIUM.contains(sitioKey)`, so
-- the real functional dependency is `url -> sitio -> es_premium`. That is the
-- textbook 3NF violation this change exists to close, and `sitio.es_premium`
-- (V18) already holds the authoritative value.
--
-- ─── WHY IT IS RESOLVED IN JAVA AND NOT BY A JOIN ────────────────────────
--
-- The design framed this as a binary: read the value through `LEFT JOIN sitio`
-- or keep the denormalized column. Both were measured against the live dev
-- catalog (6540 products, `cargarProductos()` as the gating query, 5 rounds
-- interleaved so drift in machine state hits both sides equally):
--
--     LEFT JOIN sitio      10.004 ms   vs 7.814 ms baseline   +28.03%
--     resolved in Java      7.858 ms   vs 8.165 ms baseline    -3.76%
--
-- The pre-committed abort threshold was 5%, so the JOIN was out — but keeping
-- the column would have been a denormalization justified by a number that only
-- ever applied to ONE way of reading it. `marca_premium` is never a filter,
-- never a sort key and never a facet: `CatalogQueryRepository` does not
-- mention it, and `CatalogoEndpoints` only emits it into the JSON payload. So
-- the third path costs nothing — `SiteRegistry` already holds the site map in
-- memory (≤30 rows, loaded once), and `ProductRowMapper` resolves premium with
-- a hash lookup at the point it is already building the row, AFTER the
-- ResultSet is in hand and therefore outside the query plan entirely.
--
-- ─── THE FUNCTION RE-COPY, AND WHY IT SHIPS IN THIS SAME FILE ────────────
--
-- `sp_upsert_run` writes `marca_premium`, so dropping the column breaks it.
-- Postgres does NOT validate plpgsql bodies when a column is dropped — the
-- body is late-bound — so a migration that drops the column without re-copying
-- the function applies perfectly cleanly and the break surfaces at the first
-- upsert instead, where `ProductRepository` (see the swallowed-error path)
-- logs it and returns `UpsertStats(0,0,0,0)`. That reads as "0 nuevos", never
-- as an error: an entire scrape writing nothing, in silence.
--
-- The extension plan had this as V22 (drop) + V23 (re-copy). Folding both into
-- one migration removes the window where a live function references a dropped
-- column, and drops the re-copy count for this extension from two to one. The
-- function is replaced FIRST, then the column goes.
--
-- THREE sites change, and the third is why `StoredProcedureDriftTest` spells
-- them out instead of trusting review: `rg marca_premium` finds only TWO of
-- them. The INSERT VALUES expression reads the camelCase JSON key
-- `(r->>'marcaPremium')`, so a grep-driven cleanup removes the column from the
-- list and from the SET, leaves the value behind, and produces an INSERT with
-- 26 columns and 27 values — which, again, fails silently at runtime.
--
-- Rollback: docs/ARCHITECTURE.md, executed verbatim by V22RollbackRoundTripTest.

-- ═══════════════════════════════════════════════════════════════════════
-- Function re-copy #5 — V21's ENTIRE body carried forward verbatim, with
-- exactly three changes, all of them the removal of marca_premium.
-- ═══════════════════════════════════════════════════════════════════════

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

-- The column goes only after the function that wrote it is already replaced.
ALTER TABLE productos DROP COLUMN marca_premium;
