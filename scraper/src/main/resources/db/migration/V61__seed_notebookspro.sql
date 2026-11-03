-- V61__seed_notebookspro.sql
--
-- NotebooksPro: notebooks, monitors and mini PCs on WooCommerce (Flatsome theme), 133 products
-- in the Store API (measured 2026-11-03). Seed only: 'woocommerce' is already a valid plataforma
-- and 'tecnologia' a valid rubro_forzado. Not own-label (resells HP, Lenovo, Cymax...), so no
-- monomarca flag and no marca row.
INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES
    ('Notebookspro', 'notebookspro', 'woocommerce', false, 'tecnologia', 'config')
ON CONFLICT (nombre) DO NOTHING;
