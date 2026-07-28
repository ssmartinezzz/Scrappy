-- V3__manual_classification_lock.sql — manual-classification-lock
--
-- Once a product's classification is human-confirmed (POST /api/agent/apply),
-- no subsequent scrape may overwrite it. Enforcement is SQL, authoritative,
-- and covers every write path:
--   1. sp_upsert_run (this file): the 5 locked columns are guarded by a CASE
--      that only lets EXCLUDED.<col> through when the row is unlocked.
--   2. actualizarCategoria / actualizarNormalizacion (DatabaseService.java,
--      Java-side): both gain "AND bloqueado_por IS NULL" (design D5) — see
--      that class, not this migration.
--
-- Decision D1 (architecture/session-readiness, obs #773): the lock records
-- WHO confirmed it, never a bare boolean. The CHECK constraint below makes
-- "locked" unambiguous: bloqueado_por/bloqueado_at are both NULL (unlocked)
-- or both populated with a non-blank actor (locked) — a blank actor can
-- never masquerade as locked. The second branch tests "bloqueado_por IS NOT
-- NULL" before "bloqueado_por <> ''" (review fix F4) — SQL's three-valued
-- logic means "NULL <> ''" evaluates to NULL rather than FALSE, so without
-- the explicit IS NOT NULL guard, the half-locked state (bloqueado_por NULL,
-- bloqueado_at populated) made the whole OR expression NULL — which
-- PostgreSQL accepts for a CHECK constraint (only an explicit FALSE is
-- rejected) — silently allowing exactly the state this constraint claims to
-- prevent.
--
-- V1__baseline.sql is NEVER edited (Flyway validates checksums). sp_upsert_run
-- has no partial redefinition in Postgres, so this CREATE OR REPLACE carries
-- V1's ENTIRE function body (V1__baseline.sql:227-324) verbatim, with exactly
-- 5 lines changed inside DO UPDATE SET (categoria, genero, sub_categoria,
-- marca, rubro) — mechanically guaranteed by SpUpsertRunDriftTest, not by
-- careful copying alone.

ALTER TABLE productos
    ADD COLUMN bloqueado_por TEXT,
    ADD COLUMN bloqueado_at TEXT,
    ADD CONSTRAINT chk_productos_bloqueo CHECK (
        (bloqueado_por IS NULL AND bloqueado_at IS NULL)
        OR (bloqueado_por IS NOT NULL AND bloqueado_por <> '' AND bloqueado_at IS NOT NULL)
    );

-- agent_reclassify_audit gains the actor column session-readiness already
-- flagged as missing (obs #773) — nullable: pre-seam rows are permanently
-- anonymous, and NULL says so honestly.
ALTER TABLE agent_reclassify_audit
    ADD COLUMN applied_by TEXT;

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

        SELECT precio INTO v_prev_precio FROM productos WHERE url = r->>'url' AND activo = 1;
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
            COALESCE((r->>'mlScore')::INTEGER, 50), COALESCE((r->>'mlOferta')::INTEGER, 0),
            COALESCE(r->>'mlTendencia', ''), COALESCE(r->>'mlSegment', 'standard'),
            COALESCE((r->>'mlZscore')::DOUBLE PRECISION, 0.0),
            COALESCE(r->>'rubro', 'indumentaria'), COALESCE(r->>'marca', ''),
            COALESCE((r->>'gymrat')::INTEGER, 0), COALESCE((r->>'marcaPremium')::INTEGER, 0),
            COALESCE((r->>'cantidadUnidades')::INTEGER, 1), COALESCE(r->>'subCategoria', ''),
            v_fit, v_estampado, v_escote, v_color, 1, r->>'now', r->>'now'
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
            activo            = 1,
            touched_at        = EXCLUDED.touched_at;

        IF v_prev_precio IS NULL THEN
            v_nuevos := v_nuevos + 1;
            INSERT INTO precio_historico (url, precio, fecha)
            VALUES (r->>'url', v_new_precio, r->>'fecha')
            ON CONFLICT (url, fecha) DO NOTHING;
        ELSIF abs(v_prev_precio - v_new_precio) > 0.01 THEN
            v_actualizados := v_actualizados + 1;
            INSERT INTO precio_historico (url, precio, fecha)
            VALUES (r->>'url', v_new_precio, r->>'fecha')
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
