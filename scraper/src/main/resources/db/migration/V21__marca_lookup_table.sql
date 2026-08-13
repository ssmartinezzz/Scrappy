-- V21__marca_lookup_table.sql — close-1nf-and-3nf-foundation (3NF extension)
-- (design E4)
--
-- Natural key, V13's reasons verbatim: `nombre` is already unique, stable,
-- and is what the API returns; a surrogate id buys a JOIN per read and id
-- plumbing through the API for nothing. Table and not CHECK because this is
-- V13 territory (dozens of values), not V6's (a handful).
--
-- The "" sentinel: NULL in the DB, "" at the Java boundary. An FK asserts a
-- REFERENCE, and there is no brand to reference when BrandExtractor
-- abstains — unlike V6's CHECK, which constrains a VALUE and could let ''
-- pass as one of its own domain members. Seeding a ('') row would make the
-- empty string a brand the marca facet enumerates; storing NULL keeps
-- abstention meaning exactly what it means everywhere else in this schema.
--
-- Seed is 58 rows (measured by parsing BrandExtractor.MARCAS, CODE-3 — not
-- the 59 the design doc estimated before counting; corrected here, not
-- silently matched to the wrong number). This static seed exists ONLY so
-- the FK below can be VALID at migrate time — MarcaSeeder (an
-- ApplicationRunner, ordered first) re-seeds from BrandExtractor.MARCAS on
-- every boot with ON CONFLICT DO NOTHING, so a future curated brand is a
-- one-line edit to MARCAS, never a new migration.

CREATE TABLE marca (
    nombre TEXT PRIMARY KEY
);

INSERT INTO marca (nombre) VALUES
    ('Nike'),
    ('Adidas'),
    ('Puma'),
    ('Reebok'),
    ('New Balance'),
    ('Asics'),
    ('Saucony'),
    ('Brooks'),
    ('Hoka'),
    ('On Running'),
    ('Salomon'),
    ('Mizuno'),
    ('Under Armour'),
    ('Fila'),
    ('Umbro'),
    ('Vans'),
    ('Converse'),
    ('DC'),
    ('Etnies'),
    ('Volcom'),
    ('Quiksilver'),
    ('Billabong'),
    ('The North Face'),
    ('Columbia'),
    ('Patagonia'),
    ('Timberland'),
    ('Merrell'),
    ('Topper'),
    ('Flecha'),
    ('Jaguar'),
    ('Gola'),
    ('Penalty'),
    ('Olympikus'),
    ('Lacoste'),
    ('Tommy'),
    ('Calvin Klein'),
    ('Levi''s'),
    ('Levis'),
    ('Wrangler'),
    ('Champion'),
    ('Kappa'),
    ('Ellesse'),
    ('Le Coq Sportif'),
    ('Fred Perry'),
    ('Caterpillar'),
    ('Keen'),
    ('Palladium'),
    ('Crocs'),
    ('Birkenstock'),
    ('Bulks'),
    ('Fuark'),
    ('Harvey Willys'),
    ('Harvey'),
    ('Gold Nutrition'),
    ('Star Nutrition'),
    ('Xtrenght'),
    ('ENA'),
    ('BSA');

-- Abstention sentinel: "" is not a brand reference. V19 already cleared most
-- of these (site-name fallback backfill); this covers whatever still slipped
-- through (blank strings that were never a site-name match, or rows written
-- between V19 and this migration).
UPDATE productos SET marca = NULL WHERE btrim(coalesce(marca, '')) = '';

-- V1__baseline.sql:47 gave `marca` a column-level `DEFAULT ''` — found only
-- by running the FK against the real suite (CODE-3: the "blast radius is
-- near zero" claim in the design doc covered the READ side only). Any INSERT
-- that omits `marca` (every raw-SQL test fixture that only sets the columns
-- it cares about, and there are several) would fall back to '' and violate
-- the FK immediately. NULL is the correct default now that '' means
-- something specific (a brand reference) rather than "whatever the column
-- happens to be": dropping the default makes an omitted column behave the
-- same as an explicit NULL, matching the FK's own abstention semantics.
ALTER TABLE productos ALTER COLUMN marca DROP DEFAULT;

ALTER TABLE productos
    ADD CONSTRAINT fk_productos_marca
    FOREIGN KEY (marca) REFERENCES marca(nombre);

-- ═══════════════════════════════════════════════════════════════════════
-- Function re-copy #4 — sp_upsert_run carries V17's ENTIRE body forward
-- verbatim, with exactly ONE line changed: the INSERT VALUES expression for
-- marca gains nullif(r->>'marca','') instead of COALESCE(r->>'marca', '').
--
-- NOT deferred to a later migration in this extension (design E6 originally
-- planned to fold this into a single V23 alongside the marca_premium
-- removal and the sitio get-or-create pre-step). Discovered mid-apply: the
-- FK just added above rejects '' immediately — Java's BrandExtractor
-- already emits marca:"" for an abstained product, so v_upsert_run's old
-- COALESCE(r->>'marca', '') writes '' whenever the field is genuinely
-- absent from the JSON, but the field is essentially NEVER absent (Java
-- always includes it), so every abstained product's UPSERT would violate
-- fk_productos_marca from the moment this migration applies — not a
-- deploy-time race (Flyway applies V20-V23 together before any real scrape
-- can run), but a same-commit problem: TEST-1 requires the FULL suite green
-- at every commit, and dozens of existing tests upsert abstained-brand
-- fixtures. Splitting the recopy here — one substitution now, the other two
-- in V23 — costs a second re-copy but keeps every commit in this extension
-- independently green, which a single deferred V23 could not.
--
-- Mechanically guaranteed by StoredProcedureDriftTest's new V17 -> V21 hop
-- and its single declared Substitution.
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
            COALESCE(r->>'rubro', 'indumentaria'), nullif(r->>'marca',''),
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
