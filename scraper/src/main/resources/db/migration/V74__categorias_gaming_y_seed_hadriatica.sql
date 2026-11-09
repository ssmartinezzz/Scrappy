-- V74__categorias_gaming_y_seed_hadriatica.sql
--
-- Hadriatica (hadriatica.com): videojuegos, consolas, figuras, peluches y TCG sobre Tiendanube.
-- 'tiendanube' ya es una plataforma valida; sin rubro_forzado porque el rubro lo resuelve cada
-- producto por su categoria (Consola/Videojuego -> tecnologia, el resto -> juguetes).
-- Lo que no matchea ninguna keyword (manga, merch) cae en 'Otros', y rubro_por_defecto = 'juguetes'
-- evita que RubroResolver lo mande a 'indumentaria'.
INSERT INTO categoria (nombre) VALUES
    ('Videojuego'), ('Consola'), ('Figura Coleccionable'), ('Peluche'), ('Juego de Mesa'), ('Libro')
ON CONFLICT (nombre) DO NOTHING;

-- Rubro nuevo `libros` (categoria `Libro`): se re-lista el dominio COMPLETO de productos.rubro.
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar',
                                          'deporte', 'juguetes', 'libros'));

ALTER TABLE sitio DROP CONSTRAINT sitio_rubro_por_defecto_check;
ALTER TABLE sitio ADD CONSTRAINT sitio_rubro_por_defecto_check
    CHECK (rubro_por_defecto IS NULL
           OR rubro_por_defecto IN ('tecnologia','suplementos','oficina','construccion','hogar','juguetes'));

INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Hadriatica', 'hadriatica', 'tiendanube', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'juguetes' WHERE sitio_key = 'hadriatica';
