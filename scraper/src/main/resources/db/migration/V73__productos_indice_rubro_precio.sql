CREATE INDEX idx_productos_rubro_precio
    ON productos (lower(coalesce(nullif(rubro, ''), 'indumentaria')), precio, url)
    WHERE activo;
