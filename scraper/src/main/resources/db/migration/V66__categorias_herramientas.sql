INSERT INTO categoria (nombre) VALUES
    ('Taladro'), ('Amoladora'), ('Sierra'), ('Soldadora'), ('Hidrolavadora'), ('Herramienta Eléctrica'),
    ('Herramienta Manual'), ('Accesorio de Herramienta'), ('Lija'), ('Caja de Herramientas'), ('Tornillo'),
    ('Tarugo'), ('Clavo'), ('Fijación'), ('Bisagra'), ('Cerradura'), ('Candado'), ('Herraje'),
    ('Cinta Adhesiva'), ('Guante de Trabajo'), ('Protección Personal')
ON CONFLICT (nombre) DO NOTHING;
