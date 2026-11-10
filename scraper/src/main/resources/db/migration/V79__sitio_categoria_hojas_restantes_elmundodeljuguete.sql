-- V79__sitio_categoria_hojas_restantes_elmundodeljuguete.sql — las 30 hojas de El Mundo del Juguete que V78 dejó sin mapear.
--
-- Sólo datos, sin categorías nuevas. Curado con la ruta de cada hoja y los nombres que trajo el primer scrape completo
-- (2026-11-09). Con V78, quedan sin fila sólo las gift cards (71, 158, 159) y Bolsas (151), que VtexPage excluye.
--
-- En este sitio una hoja que mapea a una categoría de juguetes le gana al nombre (NormalizerService); las demás sólo
-- se usan cuando el nombre se abstiene. Por eso las hojas mezcladas van a 'Juguete' sólo cuando casi todo es juguete
-- (Outlet, Disfraces, Baño y Cuidado). Las seis que no tienen categoría canónica en ningún sitio (pilas, combos,
-- cartucheras, útiles, accesorios de viaje) van a 'Otros' explícito: quedan revisadas sin inventar una categoría.
INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) VALUES
    ('elmundodeljuguete', 41, 'Indumentaria', 'Juguete'),
    ('elmundodeljuguete', 42, 'Disfraces', 'Juguete'),
    ('elmundodeljuguete', 63, 'Cunas, Sillas y Mecedores', 'Mueble'),
    ('elmundodeljuguete', 65, 'De Paseo', 'Juguete'),
    ('elmundodeljuguete', 74, 'Pilas', 'Otros'),
    ('elmundodeljuguete', 79, 'Combos', 'Otros'),
    ('elmundodeljuguete', 98, 'Cartucheras', 'Otros'),
    ('elmundodeljuguete', 99, 'Luncheras', 'Conservadora'),
    ('elmundodeljuguete', 109, 'Muebles y Bazar', 'Mueble'),
    ('elmundodeljuguete', 112, 'Pilas y Baterías', 'Otros'),
    ('elmundodeljuguete', 116, 'Baby Call', 'Cámara'),
    ('elmundodeljuguete', 119, 'Rollers y Patines', 'Monopatín'),
    ('elmundodeljuguete', 129, 'Inflables y Salvavidas', 'Inflable'),
    ('elmundodeljuguete', 132, 'Mesas y SIllas', 'Mueble de Exterior'),
    ('elmundodeljuguete', 133, 'Carpas y Peloteros', 'Carpa'),
    ('elmundodeljuguete', 134, 'de Precisión', 'Equipamiento Deportivo'),
    ('elmundodeljuguete', 135, 'Individuales', 'Equipamiento Deportivo'),
    ('elmundodeljuguete', 136, 'Piletas', 'Pileta'),
    ('elmundodeljuguete', 137, 'Playa', 'Juguete'),
    ('elmundodeljuguete', 138, 'Accesorios', 'Accesorio de Pileta'),
    ('elmundodeljuguete', 144, 'Botellas, Vasos  y Tuppers', 'Termo y Botella'),
    ('elmundodeljuguete', 145, 'Útiles Escolares', 'Otros'),
    ('elmundodeljuguete', 146, 'Colchones Inflables y Eléctricos', 'Artículo de Camping'),
    ('elmundodeljuguete', 147, 'Baño y Cuidado', 'Juguete'),
    ('elmundodeljuguete', 153, 'Vasos y Termos', 'Termo y Botella'),
    ('elmundodeljuguete', 156, 'Accesorios de Viaje', 'Otros'),
    ('elmundodeljuguete', 157, 'Seguridad y Organización', 'Organización'),
    ('elmundodeljuguete', 160, 'Alimentación y Accesorios', 'Bazar'),
    ('elmundodeljuguete', 161, 'Paseo y Accesorios', 'Bolso'),
    ('elmundodeljuguete', 166, 'Outlet', 'Juguete')
ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria;
