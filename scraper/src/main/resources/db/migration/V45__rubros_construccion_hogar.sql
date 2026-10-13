ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar'));

ALTER TABLE sitio DROP CONSTRAINT sitio_rubro_forzado_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_rubro_forzado_check
    CHECK (rubro_forzado IN ('tecnologia','suplementos','oficina','construccion','hogar'));

INSERT INTO categoria (nombre) VALUES
    ('Placa Decorativa'), ('Vinilo Decorativo'), ('Piso'), ('Césped Sintético'),
    ('Adhesivo'), ('Moldura'), ('Planta Artificial'),
    ('Almohadón'), ('Funda'), ('Manta'), ('Mantel'), ('Alfombra'), ('Respaldo')
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Mrplacadeco',  'mrplacadeco',  'tiendanube', false, 'construccion', 'config'),
    ('Vayzenhome',   'vayzenhome',   'tiendanube', false, 'construccion', 'config'),
    ('Ambiancedeco', 'ambiancedeco', 'tiendanube', false, 'hogar',        'config')
ON CONFLICT (nombre) DO NOTHING;
