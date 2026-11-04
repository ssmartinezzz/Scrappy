INSERT INTO categoria (nombre) VALUES
    ('Planta'), ('Semilla'), ('Tierra y Fertilizante'), ('Maceta'), ('Riego'), ('Pulverizador'),
    ('Cerco y Media Sombra'), ('Decoración de Jardín'), ('Herramienta de Jardín'), ('Máquina de Jardín'), ('Gazebo y Sombrilla'), ('Reposera y Hamaca'),
    ('Mueble de Exterior'), ('Depósito de Jardín'), ('Parrilla'), ('Accesorio de Parrilla'), ('Carbón y Leña'), ('Pileta'),
    ('Inflable'), ('Químico de Pileta'), ('Accesorio de Pileta'), ('Carpa'), ('Bolsa de Dormir'), ('Conservadora'),
    ('Termo y Botella'), ('Artículo de Camping'), ('Alimento para Mascota'), ('Cama para Mascota'), ('Higiene de Mascota'), ('Accesorio para Mascota'),
    ('Lubricante'), ('Líquido Automotor'), ('Batería de Auto'), ('Escobilla de Auto'), ('Limpieza de Auto'), ('Accesorio de Auto'),
    ('Herramienta de Auto'), ('Seguridad Vial'), ('Termotanque'), ('Calefón'), ('Calefactor'), ('Ventilador'),
    ('Aire Acondicionado'), ('Heladera'), ('Lavarropas'), ('Cocina y Horno'), ('Campana y Extractor'), ('Aspiradora'),
    ('Pequeño Electrodoméstico'), ('TV y Audio')
ON CONFLICT (nombre) DO NOTHING;
