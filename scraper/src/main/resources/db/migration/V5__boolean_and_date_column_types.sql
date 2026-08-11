-- V5__boolean_and_date_column_types.sql — normalize-db-schema-fks-1nf, slice A.2
-- (spec "db-column-domains", design D2/D6)
--
-- Retypes the 8 INTEGER-boolean column NAMES (9 physical columns — "activo"
-- exists on both productos and financiacion_presets) to native BOOLEAN, and
-- the two price-history DATE columns to DATE. productos.touched_at/created_at
-- retype to TIMESTAMPTZ in this SAME migration only because sp_upsert_run and
-- sp_soft_delete_ausentes write them, and Postgres has no partial function
-- redefinition — carrying the whole body forward is the same seam V3 already
-- established. The other ~20 TEXT *_at columns cost no function re-copy and
-- ride in their own slice (A.4).
--
-- Boolean columns:
--   productos.activo, productos.gymrat, productos.marca_premium, productos.ml_oferta
--   cron_jobs.enabled, cron_jobs.force_retrain, cron_jobs.use_gpu
--   outfit_feedback_item.liked
--   financiacion_presets.activo
--
-- Date/timestamp columns:
--   precio_historico.fecha  -> DATE
--   precios_externos.fecha  -> DATE
--   productos.touched_at    -> TIMESTAMPTZ
--   productos.created_at    -> TIMESTAMPTZ
--
-- DEFAULT expressions on an INTEGER column cannot be auto-cast to BOOLEAN by
-- Postgres ("default ... cannot be cast automatically to type boolean",
-- verified empirically against postgres:16-alpine) — each retyped column
-- therefore DROPs its default before the TYPE change and SETs an explicit
-- boolean default after. `USING col <> 0` avoids relying on an int->boolean
-- explicit cast existing at all (it does on PG16, but the comparison form is
-- unambiguous on any version and treats any nonzero value as true, matching
-- how the Java/Python write paths only ever wrote 0 or 1).

-- ─── productos ───────────────────────────────────────────────────────────
ALTER TABLE productos ALTER COLUMN activo DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN activo TYPE boolean USING (activo <> 0);
ALTER TABLE productos ALTER COLUMN activo SET DEFAULT true;

ALTER TABLE productos ALTER COLUMN gymrat DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN gymrat TYPE boolean USING (gymrat <> 0);
ALTER TABLE productos ALTER COLUMN gymrat SET DEFAULT false;

ALTER TABLE productos ALTER COLUMN marca_premium DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN marca_premium TYPE boolean USING (marca_premium <> 0);
ALTER TABLE productos ALTER COLUMN marca_premium SET DEFAULT false;

ALTER TABLE productos ALTER COLUMN ml_oferta DROP DEFAULT;
ALTER TABLE productos ALTER COLUMN ml_oferta TYPE boolean USING (ml_oferta <> 0);
ALTER TABLE productos ALTER COLUMN ml_oferta SET DEFAULT false;

ALTER TABLE productos ALTER COLUMN touched_at TYPE timestamptz USING touched_at::timestamptz;
ALTER TABLE productos ALTER COLUMN created_at TYPE timestamptz USING created_at::timestamptz;

-- idx_prod_activo was built over the INTEGER column; Postgres rebuilds it
-- transparently as part of the ALTER COLUMN TYPE above (no separate DDL
-- needed — it stays named idx_prod_activo, now over the boolean column).

-- ─── cron_jobs ───────────────────────────────────────────────────────────
ALTER TABLE cron_jobs ALTER COLUMN force_retrain DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN force_retrain TYPE boolean USING (force_retrain <> 0);
ALTER TABLE cron_jobs ALTER COLUMN force_retrain SET DEFAULT false;

ALTER TABLE cron_jobs ALTER COLUMN use_gpu DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN use_gpu TYPE boolean USING (use_gpu <> 0);
ALTER TABLE cron_jobs ALTER COLUMN use_gpu SET DEFAULT true;

ALTER TABLE cron_jobs ALTER COLUMN enabled DROP DEFAULT;
ALTER TABLE cron_jobs ALTER COLUMN enabled TYPE boolean USING (enabled <> 0);
ALTER TABLE cron_jobs ALTER COLUMN enabled SET DEFAULT true;

-- ─── outfit_feedback_item ────────────────────────────────────────────────
ALTER TABLE outfit_feedback_item ALTER COLUMN liked DROP DEFAULT;
ALTER TABLE outfit_feedback_item ALTER COLUMN liked TYPE boolean USING (liked <> 0);
ALTER TABLE outfit_feedback_item ALTER COLUMN liked SET DEFAULT false;

-- outfit_feedback (legacy whole-outfit table) keeps its INTEGER liked column
-- untouched — nothing reads or writes it anymore (only a blanket DELETE in
-- FeedbackRepository.limpiarOutfitFeedback()), so retyping it buys nothing
-- and only widens this migration's blast radius.

-- ─── financiacion_presets ────────────────────────────────────────────────
ALTER TABLE financiacion_presets ALTER COLUMN activo DROP DEFAULT;
ALTER TABLE financiacion_presets ALTER COLUMN activo TYPE boolean USING (activo <> 0);
ALTER TABLE financiacion_presets ALTER COLUMN activo SET DEFAULT false;

-- ─── precio_historico / precios_externos ─────────────────────────────────
ALTER TABLE precio_historico ALTER COLUMN fecha TYPE date USING fecha::date;
ALTER TABLE precios_externos ALTER COLUMN fecha TYPE date USING fecha::date;

-- ═══════════════════════════════════════════════════════════════════════
-- Function re-copy #1 (design D1) — sp_upsert_run/sp_soft_delete_ausentes
-- carry V3's ENTIRE bodies forward verbatim (V3__manual_classification_lock.sql
-- is never edited — Flyway validates checksums), with exactly the casts this
-- migration's column retypes require:
--   activo = 1/0 (int literal)          -> activo / activo = false (boolean)
--   gymrat/marca_premium/ml_oferta int  -> boolean, sourced from the JSON
--     booleans buildRowsJson now emits (design D2) via (r->>'col')::boolean
--   r->>'now'   into touched_at/created_at -> (r->>'now')::timestamptz
--   r->>'fecha' into precio_historico.fecha -> (r->>'fecha')::date
--   p_now (text param) into touched_at      -> p_now::timestamptz
-- Mechanically guaranteed by SpUpsertRunDriftTest's generalised PREVIOUS/
-- CURRENT + declared Substitution list (task 2.8), not by careful copying.
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
            talles, ml_badge, ml_score, ml_oferta, ml_tendencia, ml_segment, ml_zscore,
            rubro, marca, gymrat, marca_premium, cantidad_unidades, sub_categoria,
            fit, estampado, escote, color_dominante, activo, touched_at, created_at
        ) VALUES (
            r->>'url', r->>'sitio', r->>'nombre', v_new_precio, r->>'precioOrig', r->>'imagenUrl',
            r->>'categoria', r->>'genero', COALESCE(r->>'talles', '[]'), COALESCE(r->>'mlBadge', ''),
            COALESCE((r->>'mlScore')::INTEGER, 50), COALESCE((r->>'mlOferta')::boolean, false),
            COALESCE(r->>'mlTendencia', ''), COALESCE(r->>'mlSegment', 'standard'),
            COALESCE((r->>'mlZscore')::DOUBLE PRECISION, 0.0),
            COALESCE(r->>'rubro', 'indumentaria'), COALESCE(r->>'marca', ''),
            COALESCE((r->>'gymrat')::boolean, false), COALESCE((r->>'marcaPremium')::boolean, false),
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
            talles            = EXCLUDED.talles,
            ml_badge          = EXCLUDED.ml_badge,
            ml_score          = EXCLUDED.ml_score,
            ml_oferta         = EXCLUDED.ml_oferta,
            ml_tendencia      = EXCLUDED.ml_tendencia,
            ml_segment        = EXCLUDED.ml_segment,
            ml_zscore         = EXCLUDED.ml_zscore,
            rubro             = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.rubro ELSE productos.rubro END,
            marca             = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.marca ELSE productos.marca END,
            gymrat            = EXCLUDED.gymrat,
            marca_premium     = EXCLUDED.marca_premium,
            cantidad_unidades = EXCLUDED.cantidad_unidades,
            sub_categoria     = CASE WHEN productos.bloqueado_por IS NULL THEN EXCLUDED.sub_categoria ELSE productos.sub_categoria END,
            fit               = COALESCE(NULLIF(EXCLUDED.fit, ''), productos.fit),
            estampado         = COALESCE(NULLIF(EXCLUDED.estampado, ''), productos.estampado),
            escote            = COALESCE(NULLIF(EXCLUDED.escote, ''), productos.escote),
            color_dominante   = COALESCE(NULLIF(EXCLUDED.color_dominante, ''), productos.color_dominante),
            activo            = true,
            touched_at        = EXCLUDED.touched_at;

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
