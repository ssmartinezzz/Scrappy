-- V112__rubro_belleza.sql
--
-- Rubro `perfumeria` is renamed `belleza`: it will also hold makeup, skin care and hair care (V113), with `Perfume`
-- and `Desodorante` as two of its categories. productos.rubro is the only column that enumerates the value
-- (sitio.rubro_forzado / rubro_por_defecto never listed it), so the CHECK is re-listed in full, the stored rows move
-- over, and nothing else changes. The old CHECK rejects `belleza` and the new one rejects `perfumeria`, hence the order:
-- drop, move the rows, add. Idempotent.
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;

UPDATE productos SET rubro = 'belleza' WHERE rubro = 'perfumeria';

ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar',
                                          'deporte', 'juguetes', 'libros', 'belleza'));
