INSERT INTO categoria (nombre) VALUES
    ('Cemento'), ('Ladrillo'), ('Árido'), ('Hierro'), ('Impermeabilizante'), ('Madera'),
    ('Tablero'), ('Chapa'), ('Aislante'), ('Placa de Yeso'), ('Perfilería'), ('Pintura'),
    ('Enduido'), ('Diluyente'), ('Accesorio de Pintura'), ('Empapelado'), ('Puerta'),
    ('Ventana'), ('Portón'), ('Mosquitero'), ('Toldo'), ('Cerámico'), ('Porcelanato'),
    ('Pastina'), ('Revestimiento'), ('Escalera')
ON CONFLICT (nombre) DO NOTHING;
