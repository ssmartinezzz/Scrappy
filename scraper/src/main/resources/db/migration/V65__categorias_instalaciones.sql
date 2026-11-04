INSERT INTO categoria (nombre) VALUES
    ('Caño'), ('Accesorio de Caño'), ('Llave de Paso'), ('Flexible'), ('Tanque de Agua'),
    ('Bomba de Agua'), ('Canaleta'), ('Regulador de Gas'), ('Cable Eléctrico'), ('Llave de Luz'),
    ('Térmica'), ('Caja Eléctrica'), ('Cablecanal'), ('Ficha y Prolongador')
ON CONFLICT (nombre) DO NOTHING;
