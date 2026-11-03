CREATE OR REPLACE FUNCTION sp_upsert_sitio_categoria(p_sitio text, p_nodos jsonb)
RETURNS integer AS $$
DECLARE
    v_key      TEXT;
    v_cantidad INTEGER;
BEGIN
    SELECT sitio_key INTO v_key FROM sitio WHERE nombre = p_sitio;
    IF v_key IS NULL OR p_nodos IS NULL OR jsonb_array_length(p_nodos) = 0 THEN
        RETURN 0;
    END IF;

    INSERT INTO sitio_categoria (sitio_key, origen_id, nombre)
    SELECT DISTINCT ON (n.origen_id) v_key, n.origen_id, n.nombre
    FROM jsonb_to_recordset(p_nodos) AS n(origen_id integer, nombre text)
    WHERE n.origen_id IS NOT NULL AND COALESCE(n.nombre, '') <> ''
    ON CONFLICT (sitio_key, origen_id) DO UPDATE SET nombre = EXCLUDED.nombre;
    GET DIAGNOSTICS v_cantidad = ROW_COUNT;

    UPDATE sitio_categoria c
    SET padre_id = padre.id
    FROM jsonb_to_recordset(p_nodos) AS n(origen_id integer, padre_origen_id integer)
    LEFT JOIN sitio_categoria padre
           ON padre.sitio_key = v_key AND padre.origen_id = n.padre_origen_id
    WHERE c.sitio_key = v_key
      AND c.origen_id = n.origen_id
      AND c.padre_id IS DISTINCT FROM padre.id;

    RETURN v_cantidad;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION sp_vincular_sitio_categoria(p_rows jsonb)
RETURNS integer AS $$
DECLARE
    v_vinculados INTEGER;
BEGIN
    UPDATE productos p
    SET sitio_categoria_id = c.id
    FROM jsonb_to_recordset(p_rows) AS r(url text, sitio text, "sitioCategoriaOrigenId" integer)
    JOIN sitio s ON s.nombre = r.sitio
    LEFT JOIN sitio_categoria c
           ON c.sitio_key = s.sitio_key AND c.origen_id = r."sitioCategoriaOrigenId"
    WHERE p.url = r.url
      AND r."sitioCategoriaOrigenId" IS NOT NULL
      AND p.sitio_categoria_id IS DISTINCT FROM c.id;
    GET DIAGNOSTICS v_vinculados = ROW_COUNT;
    RETURN v_vinculados;
END;
$$ LANGUAGE plpgsql;
