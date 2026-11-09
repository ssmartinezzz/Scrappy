-- V77__seed_elmundodeljuguete.sql — El Mundo del Juguete (elmundodeljuguete.com.ar), VTEX.
--
-- Sólo seed: 'vtex' ya es una plataforma válida y 'juguetes' ya es un rubro_por_defecto
-- válido (V74). Medido 2026-11-09: la API Legacy responde sin región, el header
-- `resources` da 8275 y ninguna categoría de primer nivel supera la ventana de 2550
-- (la mayor, Figuras de Acción y Muñecos, tiene ~1000), así que VtexPage las trae enteras.
--
-- Sitio de juguetes con una cola no juguete (blanquería, calzado, marroquinería, bazar):
-- rubro_forzado queda NULL para que RubroResolver decida por categoría; lo que ninguna
-- categoría resuelve cae en 'juguetes'. El nombre es el que arma ScraperFactory a partir
-- de la clave de config.properties (primera letra en mayúscula).
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Elmundodeljuguete', 'elmundodeljuguete', 'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'juguetes' WHERE sitio_key = 'elmundodeljuguete';
