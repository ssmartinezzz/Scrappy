-- V1__baseline.sql — decouple-services-postgres Batch 1 (design D3)
--
-- Full 15-table baseline, translated from the SQLite schema previously
-- bootstrapped at runtime by DatabaseService.crearTablas()/migrarColumna().
-- Behavior-preserving translation only (infra swap, spec "Data Round-Trip
-- Parity"):
--   AUTOINCREMENT        -> GENERATED ALWAYS AS IDENTITY
--   INSERT OR IGNORE     -> ON CONFLICT ... DO NOTHING (at call sites / procs)
--   BLOB                 -> bytea
--   REAL                 -> DOUBLE PRECISION
--   PRAGMA (WAL/busy_timeout) -> dropped entirely (Postgres MVCC, design D1)
-- Date/datetime columns stay TEXT holding the same 'yyyy-MM-dd[ HH:mm:ss]'
-- strings the Java side already formats (design D3 — explicit, in-scope
-- decision; native TIMESTAMP is a future opportunity, out of scope here).

-- ─── precios_externos ────────────────────────────────────────────────────
CREATE TABLE precios_externos (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    producto_url TEXT NOT NULL,
    sitio        TEXT NOT NULL,
    titulo       TEXT NOT NULL,
    precio       DOUBLE PRECISION NOT NULL,
    externo_url  TEXT,
    condicion    TEXT DEFAULT 'new',
    fecha        TEXT NOT NULL
);
CREATE INDEX idx_pe_url ON precios_externos(producto_url);

-- ─── productos ───────────────────────────────────────────────────────────
CREATE TABLE productos (
    url               TEXT PRIMARY KEY,
    sitio             TEXT NOT NULL,
    nombre            TEXT NOT NULL,
    precio            DOUBLE PRECISION NOT NULL,
    precio_orig       TEXT,
    imagen_url        TEXT,
    categoria         TEXT,
    genero            TEXT,
    talles            TEXT,
    ml_badge          TEXT DEFAULT '',
    ml_score          INTEGER DEFAULT 50,
    ml_oferta         INTEGER DEFAULT 0,
    ml_tendencia      TEXT DEFAULT '',
    ml_segment        TEXT DEFAULT 'standard',
    ml_zscore         DOUBLE PRECISION DEFAULT 0.0,
    rubro             TEXT DEFAULT 'indumentaria',
    marca             TEXT DEFAULT '',
    gymrat            INTEGER DEFAULT 0,
    marca_premium     INTEGER DEFAULT 0,
    cantidad_unidades INTEGER DEFAULT 1,
    sub_categoria     TEXT DEFAULT '',
    fit               TEXT DEFAULT '',
    estampado         TEXT DEFAULT '',
    escote            TEXT DEFAULT '',
    color_dominante   TEXT DEFAULT '',
    activo            INTEGER DEFAULT 1,
    touched_at        TEXT,
    created_at        TEXT
);
CREATE INDEX idx_prod_sitio  ON productos(sitio);
CREATE INDEX idx_prod_activo ON productos(activo);

-- ─── image_embeddings ────────────────────────────────────────────────────
-- Marqo-FashionSigLIP embedding cache keyed by image URL. model_version bump
-- invalidates the cache (treated as a miss) at the application layer.
CREATE TABLE image_embeddings (
    url           TEXT PRIMARY KEY,
    embedding     bytea NOT NULL,
    dim           INTEGER NOT NULL,
    model_version TEXT NOT NULL,
    computed_at   TEXT NOT NULL
);

-- ─── precio_historico ────────────────────────────────────────────────────
CREATE TABLE precio_historico (
    id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    url    TEXT NOT NULL,
    precio DOUBLE PRECISION NOT NULL,
    fecha  TEXT NOT NULL,
    UNIQUE(url, fecha)
);
CREATE INDEX idx_hist_url ON precio_historico(url);

-- ─── ml_output ───────────────────────────────────────────────────────────
CREATE TABLE ml_output (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- ─── sitios_dinamicos ────────────────────────────────────────────────────
CREATE TABLE sitios_dinamicos (
    nombre     TEXT PRIMARY KEY,
    url        TEXT NOT NULL,
    plataforma TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- ─── categoria_stats ─────────────────────────────────────────────────────
CREATE TABLE categoria_stats (
    categoria  TEXT PRIMARY KEY,
    payload    TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- ─── favoritos ───────────────────────────────────────────────────────────
CREATE TABLE favoritos (
    url             TEXT PRIMARY KEY,
    sitio           TEXT NOT NULL,
    nombre          TEXT,
    added_at        TEXT,
    last_checked_at TEXT
);
CREATE INDEX idx_fav_sitio ON favoritos(sitio);

-- ─── outfit_feedback (legacy, whole-outfit verdict) ─────────────────────
CREATE TABLE outfit_feedback (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    genero        TEXT,
    liked         INTEGER NOT NULL DEFAULT 0,
    torso_url     TEXT,
    piernas_url   TEXT,
    calzado_url   TEXT,
    accesorio_url TEXT,
    created_at    TEXT NOT NULL
);
CREATE INDEX idx_outfit_fb_liked ON outfit_feedback(liked);

-- ─── outfit_feedback_item (per-item verdict, ADR-1) ──────────────────────
CREATE TABLE outfit_feedback_item (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    genero     TEXT,
    slot       TEXT NOT NULL,
    url        TEXT NOT NULL,
    liked      INTEGER NOT NULL DEFAULT 0,
    estilo     TEXT NOT NULL DEFAULT 'gym',
    created_at TEXT NOT NULL
);
CREATE INDEX idx_ofi_liked  ON outfit_feedback_item(liked);
CREATE INDEX idx_ofi_estilo ON outfit_feedback_item(estilo);

-- ─── categoria_dismiss ("no me interesa" en el feed) ─────────────────────
CREATE TABLE categoria_dismiss (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    categoria  TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_catdismiss_cat ON categoria_dismiss(categoria);

-- ─── financiacion_presets ────────────────────────────────────────────────
CREATE TABLE financiacion_presets (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label       TEXT NOT NULL,
    recargo_pct DOUBLE PRECISION NOT NULL,
    cuotas      INTEGER NOT NULL,
    activo      INTEGER DEFAULT 0,
    created_at  TEXT NOT NULL
);

-- ─── saved_outfits ───────────────────────────────────────────────────────
CREATE TABLE saved_outfits (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre           TEXT NOT NULL DEFAULT 'Outfit',
    slots_json       TEXT NOT NULL,
    suplementos_json TEXT,
    total_estimado   DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL
);

-- ─── cron_jobs / cron_executions (scraper-cronjobs) ─────────────────────
CREATE TABLE cron_jobs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT    NOT NULL,
    precio_min    DOUBLE PRECISION NOT NULL DEFAULT 0,
    precio_max    DOUBLE PRECISION NOT NULL DEFAULT 0,
    sitios_json   TEXT    NOT NULL DEFAULT '[]',
    force_retrain INTEGER NOT NULL DEFAULT 0,
    use_gpu       INTEGER NOT NULL DEFAULT 1,
    cron_expr     TEXT    NOT NULL,
    enabled       INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL,
    last_run_at   TEXT,
    next_run_at   TEXT
);
CREATE INDEX idx_cron_jobs_enabled ON cron_jobs(enabled, next_run_at);

CREATE TABLE cron_executions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         BIGINT NOT NULL REFERENCES cron_jobs(id),
    started_at     TEXT    NOT NULL,
    finished_at    TEXT,
    status         TEXT    NOT NULL,
    skipped_reason TEXT,
    duration_ms    INTEGER,
    log_output     TEXT
);
CREATE INDEX idx_cron_exec_job ON cron_executions(job_id, id DESC);

-- ═══════════════════════════════════════════════════════════════════════
-- Write-path stored procedures (design D2) — the price-change decision and
-- the upsert/history-insert/soft-delete logic move server-side so
-- concurrent scrape/cron/API callers never race a Java-side
-- read-then-compare snapshot. UNIQUE(url,fecha) + ON CONFLICT DO NOTHING
-- makes concurrent double-insert of the same day's price idempotent.
-- ═══════════════════════════════════════════════════════════════════════

-- sp_upsert_run: upserts a batch of product rows (JSON array) + inserts
-- precio_historico rows per the exact invariant preserved from
-- DatabaseService.upsertProductos()/upsertParcial():
--   URL nueva    -> INSERT + historial
--   precio igual -> touched_at only, no historial row
--   precio cambio-> UPDATE + historial row
-- p_include_visual mirrors the fit/estampado/escote/color_dominante
-- additive-fill invariant (RELY-001): when false (upsertParcial — visual
-- attrs not computed yet at that pipeline stage), those 4 columns are left
-- untouched on conflict, same as a blank incoming value would already do
-- via COALESCE(NULLIF(...)).
--
-- Row shape (each element of p_rows): {
--   "url","sitio","nombre","precio","precioOrig","imagenUrl","categoria",
--   "genero","talles","mlBadge","mlScore","mlOferta","mlTendencia",
--   "mlSegment","mlZscore","rubro","marca","gymrat","marcaPremium",
--   "cantidadUnidades","subCategoria","fit","estampado","escote",
--   "colorDominante","now","fecha"
-- }
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
            categoria         = EXCLUDED.categoria,
            genero            = EXCLUDED.genero,
            talles            = EXCLUDED.talles,
            ml_badge          = EXCLUDED.ml_badge,
            ml_score          = EXCLUDED.ml_score,
            ml_oferta         = EXCLUDED.ml_oferta,
            ml_tendencia      = EXCLUDED.ml_tendencia,
            ml_segment        = EXCLUDED.ml_segment,
            ml_zscore         = EXCLUDED.ml_zscore,
            rubro             = EXCLUDED.rubro,
            marca             = EXCLUDED.marca,
            gymrat            = EXCLUDED.gymrat,
            marca_premium     = EXCLUDED.marca_premium,
            cantidad_unidades = EXCLUDED.cantidad_unidades,
            sub_categoria     = EXCLUDED.sub_categoria,
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

-- sp_soft_delete_ausentes: deactivates every active product NOT present in
-- p_urls (a full scrape run's URL set). Empty array -> everyone soft-deleted,
-- same as the prior Java loop over `activos.filter(u -> !urlsPresentes...)`.
CREATE OR REPLACE FUNCTION sp_soft_delete_ausentes(p_urls text[], p_now text)
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    UPDATE productos
    SET activo = 0, touched_at = p_now
    WHERE activo = 1 AND NOT (url = ANY(p_urls));
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;
