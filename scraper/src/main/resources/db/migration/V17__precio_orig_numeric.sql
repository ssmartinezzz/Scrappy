-- V17__precio_orig_numeric.sql — close-1nf-and-3nf-foundation
-- (spec "catalog-price-normalization", design DD1/DD2/DD7)
--
-- productos.precio_orig moves from TEXT (the raw scraped string, in whatever
-- format the store used) to DOUBLE PRECISION. An unparseable/absent original
-- price is NULL, never a sentinel string or 0 (D1) — that is what let
-- "% Oferta" sort as a -666x artifact instead of NULLS LAST.
--
-- sp_parse_precio_ar mirrors ar.scraper.aggregator.text.PrecioParser's 8-rule
-- AR-locale contract byte for byte (both are pinned against the SAME fixture,
-- scraper/src/test/resources/price-parser-cases.tsv, by PrecioParserTest and
-- V17BackfillParityTest respectively — design DD2's anti-drift mechanism).
-- It exists ONLY for this one-time backfill of the existing TEXT column's
-- messy values and is dropped immediately after — the ONGOING write path
-- (sp_upsert_run, below) never calls it: by the time a row reaches the
-- upsert, PrecioParser already ran in Java at scrape time, so the JSON value
-- is already a clean number or null and a direct cast is all that is needed.

CREATE FUNCTION sp_parse_precio_ar(raw text)
RETURNS double precision AS $$
DECLARE
    s          text;
    n_puntos   int;
    n_comas    int;
    dot_pos    int;
    int_part   text;
    frac_part  text;
    v          double precision;
BEGIN
    IF raw IS NULL OR btrim(raw) = '' THEN
        RETURN NULL;
    END IF;
    IF raw ~* '(nan|null|undefined|none)' THEN
        RETURN NULL;
    END IF;

    s := regexp_replace(raw, '[^0-9.,]', '', 'g');
    IF s = '' THEN
        RETURN NULL;
    END IF;

    n_puntos := length(s) - length(replace(s, '.', ''));
    n_comas  := length(s) - length(replace(s, ',', ''));

    IF n_comas = 1 AND n_puntos >= 1 THEN
        s := replace(replace(s, '.', ''), ',', '.');
    ELSIF n_comas = 1 AND n_puntos = 0 THEN
        s := replace(s, ',', '.');
    ELSIF n_puntos = 1 AND n_comas = 0 THEN
        dot_pos   := position('.' in s);
        int_part  := substring(s from 1 for dot_pos - 1);
        frac_part := substring(s from dot_pos + 1);
        IF length(frac_part) = 3 THEN
            s := replace(s, '.', '');
        ELSIF length(frac_part) <= 2 AND length(int_part) <= 3 THEN
            -- se conserva tal cual: decimal real
            NULL;
        ELSE
            s := replace(s, '.', '');
        END IF;
    ELSE
        s := replace(replace(s, '.', ''), ',', '');
    END IF;

    BEGIN
        v := s::double precision;
    EXCEPTION WHEN others THEN
        RETURN NULL;
    END;

    IF v > 0 AND v < 100000000 THEN
        RETURN v;
    ELSE
        RETURN NULL;
    END IF;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

ALTER TABLE productos
    ALTER COLUMN precio_orig TYPE double precision
    USING sp_parse_precio_ar(precio_orig);

DROP FUNCTION sp_parse_precio_ar(text);

-- ═══════════════════════════════════════════════════════════════════════
-- Function re-copy #3 (design DD7) — sp_upsert_run carries V7's ENTIRE body
-- forward verbatim (V7 is never edited: Flyway validates checksums), with
-- exactly ONE line changed: the INSERT VALUES expression for precio_orig
-- gains a (::DOUBLE PRECISION) cast, now that the column itself is numeric.
-- The ON CONFLICT line (`precio_orig = EXCLUDED.precio_orig`) needs NO edit —
-- EXCLUDED already carries the typed value once the INSERT VALUES cast lands.
--
-- Mechanically guaranteed by StoredProcedureDriftTest's V7 -> V17 hop and its
-- single declared Substitution, not by careful copying.
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
            r->>'url', r->>'sitio', r->>'nombre', v_new_precio, (r->>'precioOrig')::DOUBLE PRECISION, r->>'imagenUrl',
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
