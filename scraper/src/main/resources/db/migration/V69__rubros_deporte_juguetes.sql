ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar',
                                          'deporte', 'juguetes'));

INSERT INTO categoria (nombre) VALUES
    ('Bicicleta'), ('Monopatín'), ('Repuesto de Bicicleta'), ('Pesas'), ('Accesorio de Fitness'),
    ('Kayak y Bote'), ('Equipamiento Deportivo'), ('Juguete'), ('Cama Elástica')
ON CONFLICT (nombre) DO NOTHING;
