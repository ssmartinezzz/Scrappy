INSERT INTO categoria (nombre) VALUES
    ('Sanitario'), ('Grifería'), ('Ducha'), ('Bañera'), ('Mampara'), ('Vanitory'), ('Bacha'), ('Accesorio de Baño'),
    ('Mueble'), ('Placard'), ('Cama'), ('Colchón'), ('Mesa'), ('Sillón'), ('Estantería'), ('Caja y Canasto'),
    ('Basurero'), ('Tendedero'), ('Colgador'), ('Artículo de Limpieza'), ('Sistema Modular'), ('Espejo'), ('Cortina'),
    ('Ropa de Cama'), ('Toalla'), ('Decoración'), ('Bazar')
ON CONFLICT (nombre) DO NOTHING;
