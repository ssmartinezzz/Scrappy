INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Easy', 'easy', 'vtex', false, NULL, 'config')
ON CONFLICT (nombre) DO NOTHING;
