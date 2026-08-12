-- V7__product_multivalue_child_tables.sql — normalize-db-schema-fks-1nf, slice B
-- (spec "db-multivalue-normalization", design D3/D4/D5)
--
-- Moves the two repeating groups out of `productos` and into child tables:
--   productos.talles   (a JSON array serialized into TEXT) -> producto_talle
--   productos.ml_badge (a comma-delimited string)          -> producto_badge
-- Both were 1NF violations: one column holding a list, queryable only with
-- LIKE, and unable to carry a foreign key or a per-value constraint.
--
-- Ordering is modelled explicitly with `posicion`, it is not an accident of
-- scan order: ml_badge is documented "comma-delimited, principal-first" and
-- badges().get(0) IS the principal badge. `PRIMARY KEY (url, badge)` with no
-- ordinal would have silently deduped and made list equality depend on how
-- Postgres feels like scanning that day (design D3). talles carries no proven
-- ordering semantics, but one shape for both tables costs nothing (CODE-6).
--
-- No secondary index on url: Postgres does not auto-index FK child columns,
-- and the PK's leading column IS that index.

CREATE TABLE producto_talle (
    url      TEXT     NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
    posicion SMALLINT NOT NULL,
    talle    TEXT     NOT NULL,
    PRIMARY KEY (url, posicion)
);

CREATE TABLE producto_badge (
    url      TEXT     NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
    posicion SMALLINT NOT NULL,
    badge    TEXT     NOT NULL,
    PRIMARY KEY (url, posicion)
);

-- ─── Backfill (design D5) ────────────────────────────────────────────────
-- Covers activo=false rows on purpose: obtenerProducto() never filtered
-- `activo`, so a live-only backfill would silently empty the sizes of every
-- discontinued product (6914 of 13543 rows on the dev DB).
--
-- The `~ '^\s*\['` guard is not decoration: `talles` is unconstrained TEXT and
-- a single non-JSON value would abort the whole migration on a user's install.
-- It is a filter, not a validity proof — a value like `[oops` still aborts;
-- Postgres has no try-cast, and a per-row exception handler would trade a loud
-- failure for a silent partial backfill. A `WITH ORDINALITY` position is kept
-- even when a blank element is filtered out, so positions can have gaps; only
-- their ORDER is load-bearing.
--
-- The `-- >>> backfill:<table>` markers are load-bearing: BackfillSqlTest
-- executes exactly the text between them, so the tested SQL and the shipped
-- SQL cannot drift apart.

-- >>> backfill:producto_talle
INSERT INTO producto_talle (url, posicion, talle)
SELECT p.url, t.ord::smallint, t.val
FROM productos p,
     LATERAL jsonb_array_elements_text(
       CASE WHEN p.talles ~ '^\s*\[' THEN p.talles::jsonb ELSE '[]'::jsonb END
     ) WITH ORDINALITY AS t(val, ord)
WHERE t.val <> '';
-- <<< backfill:producto_talle

-- >>> backfill:producto_badge
INSERT INTO producto_badge (url, posicion, badge)
SELECT p.url, b.ord::smallint, b.val
FROM productos p,
     LATERAL string_to_table(COALESCE(p.ml_badge, ''), ',') WITH ORDINALITY AS b(val, ord)
WHERE b.val <> '';
-- <<< backfill:producto_badge

-- The child tables are now the SOLE source of truth. Keeping the columns as a
-- read-only shadow would mean two answers to the same question and a window in
-- which they disagree; rollback is a forward migration that re-adds them and
-- aggregates back (json_agg/string_agg ORDER BY posicion), lossless precisely
-- because `posicion` exists.
ALTER TABLE productos DROP COLUMN talles;
ALTER TABLE productos DROP COLUMN ml_badge;

-- ═══════════════════════════════════════════════════════════════════════
-- Function re-copy #2 (design D1) — sp_upsert_run carries V5's ENTIRE body
-- forward verbatim (V5 is never edited: Flyway validates checksums), with
-- exactly this migration's change applied:
--   - talles/ml_badge drop out of the productos INSERT column list, its
--     VALUES tail and its DO UPDATE SET clause (the columns no longer exist)
--   - each row gains a DELETE + INSERT…WITH ORDINALITY pair per child table
--
-- The writes are set-based PER PRODUCT, inside the existing loop (design D4):
-- +4 statements per row (~54k on a full 13543-row run, server-side, one
-- transaction, local socket). A batch rewrite over the whole p_rows array is
-- measurably cheaper but restructures the function enough that the drift
-- test's "everything else is identical" assertion loses its meaning — on the
-- single riskiest migration of the change. That rewrite is a follow-up gated
-- on a measurement (CODE-3), not on an opinion.
--
-- DELETE-then-INSERT, never ON CONFLICT: a shrinking size list must not leave
-- stale rows behind, which is exactly what `talles` being OVERWRITTEN (not
-- fill-only) has always meant.
--
-- Mechanically guaranteed by StoredProcedureDriftTest's V5 -> V7 hop and its
-- declared Substitution list, not by careful copying.
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
            rubro, marca, gymrat, marca_premium, cantidad_unidades, sub_categoria,
            fit, estampado, escote, color_dominante, activo, touched_at, created_at
        ) VALUES (
            r->>'url', r->>'sitio', r->>'nombre', v_new_precio, r->>'precioOrig', r->>'imagenUrl',
            r->>'categoria', r->>'genero',
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
