-- V75__seed_dellorean.sql — Dellorean (dellorean.com.ar), Tiendanube clásico.
--
-- Sólo seed: 'tiendanube' ya es una plataforma válida. Medido 2026-11-09:
-- /productos/ lista 244 productos de a 12, ?page=N pagina de verdad (la 21
-- trae 5, la 22 ninguno) y las cards traen [data-product-id], así que lo lee
-- el extractor compartido. La API REST de Tiendanube da 404.
--
-- Sitio mixto: escritorios y sillas, cámaras, cargadores, auriculares,
-- cafeteras, gazebos. rubro_forzado queda NULL para que RubroResolver decida
-- por categoría (oficina, hogar, tecnologia); la cola cae en 'tecnologia'.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Dellorean', 'dellorean', 'tiendanube', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;

UPDATE sitio SET rubro_por_defecto = 'tecnologia' WHERE sitio_key = 'dellorean';
