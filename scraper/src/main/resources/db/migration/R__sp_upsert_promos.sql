-- R__sp_upsert_promos.sql — LA definición de sp_upsert_promos. Se edita ACÁ (ver R__sp_upsert_run.sql).
--
-- Persists the payment promos of one batch, set-based, inside the caller's transaction (no COMMIT).
-- It does NOT swallow errors: a CHECK or FK violation raises so the run's transaction rolls back.
--
-- p_promos: [{sitio, medioPago, tipoMedio, tipo, cuotas, pct, alcance, vigenciaDesde, vigenciaHasta,
--             textoFuente, urls}]  (sitio = sitio.nombre; urls only for alcance 'producto').
-- p_sitios: the sitio.nombre values the run actually looked at. Not-seen promos are deactivated only
--           inside that scope; a site outside it is untouched. An empty/NULL array deactivates nothing.
-- Unknown sites and unknown product urls are ignored; medio_pago names are stored as given (the Java
-- side owns canonicalization).
CREATE OR REPLACE FUNCTION sp_upsert_promos(p_promos jsonb, p_sitios text[])
RETURNS integer AS $$
DECLARE
    v_now       TIMESTAMPTZ := clock_timestamp();
    v_promos    INTEGER := 0;
BEGIN
    IF p_promos IS NOT NULL AND jsonb_array_length(p_promos) > 0 THEN
        INSERT INTO medio_pago (nombre, tipo)
        SELECT DISTINCT ON (r."medioPago") r."medioPago", COALESCE(r."tipoMedio", 'medio')
        FROM jsonb_to_recordset(p_promos) AS r("medioPago" text, "tipoMedio" text)
        WHERE COALESCE(r."medioPago", '') <> ''
        ORDER BY r."medioPago"
        ON CONFLICT (nombre) DO NOTHING;

        INSERT INTO promo_financiacion
            (sitio_key, medio_pago_id, tipo, cuotas, pct, alcance,
             vigencia_desde, vigencia_hasta, texto_fuente, activo, primer_visto_at, visto_at)
        SELECT DISTINCT ON (s.sitio_key, m.id, r.tipo, r.alcance, COALESCE(r.cuotas, 0), COALESCE(r.pct, 0))
               s.sitio_key, m.id, r.tipo, r.cuotas, r.pct, r.alcance,
               r."vigenciaDesde", r."vigenciaHasta", r."textoFuente", true, v_now, v_now
        FROM jsonb_to_recordset(p_promos) AS r(sitio text, "medioPago" text, tipo text, cuotas smallint,
             pct numeric, alcance text, "vigenciaDesde" date, "vigenciaHasta" date, "textoFuente" text)
        JOIN sitio s      ON s.nombre = r.sitio
        JOIN medio_pago m ON m.nombre = r."medioPago"
        ORDER BY s.sitio_key, m.id, r.tipo, r.alcance, COALESCE(r.cuotas, 0), COALESCE(r.pct, 0)
        ON CONFLICT (sitio_key, medio_pago_id, tipo, alcance, COALESCE(cuotas, 0), COALESCE(pct, 0))
        DO UPDATE SET activo = true,
                      visto_at = EXCLUDED.visto_at,
                      vigencia_desde = EXCLUDED.vigencia_desde,
                      vigencia_hasta = EXCLUDED.vigencia_hasta,
                      texto_fuente = EXCLUDED.texto_fuente;
        GET DIAGNOSTICS v_promos = ROW_COUNT;

        INSERT INTO promo_financiacion_producto (promo_id, url, visto_at)
        SELECT DISTINCT p.id, u.url, v_now
        FROM jsonb_to_recordset(p_promos) AS r(sitio text, "medioPago" text, tipo text, cuotas smallint,
             pct numeric, alcance text, urls jsonb)
        JOIN sitio s      ON s.nombre = r.sitio
        JOIN medio_pago m ON m.nombre = r."medioPago"
        JOIN promo_financiacion p
          ON p.sitio_key = s.sitio_key AND p.medio_pago_id = m.id AND p.tipo = r.tipo
         AND p.alcance = r.alcance
         AND COALESCE(p.cuotas, 0) = COALESCE(r.cuotas, 0) AND COALESCE(p.pct, 0) = COALESCE(r.pct, 0)
        CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(r.urls, '[]'::jsonb)) AS u(url)
        JOIN productos pr ON pr.url = u.url
        WHERE r.alcance = 'producto'
        ON CONFLICT (promo_id, url) DO UPDATE SET visto_at = EXCLUDED.visto_at;
    END IF;

    IF p_sitios IS NOT NULL AND cardinality(p_sitios) > 0 THEN
        UPDATE promo_financiacion p
        SET activo = false
        FROM sitio s
        WHERE s.sitio_key = p.sitio_key AND s.nombre = ANY(p_sitios)
          AND p.activo AND p.visto_at < v_now;

        DELETE FROM promo_financiacion_producto l
        USING promo_financiacion p, sitio s
        WHERE p.id = l.promo_id AND s.sitio_key = p.sitio_key AND s.nombre = ANY(p_sitios)
          AND l.visto_at < v_now;
    END IF;

    RETURN v_promos;
END;
$$ LANGUAGE plpgsql;
