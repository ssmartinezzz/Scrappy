-- V90__rubro_perfumeria.sql
--
-- Rubro nuevo `perfumeria` (categoria `Perfume`, que ya existia pero caia en indumentaria o en el rubro por
-- defecto del sitio). Re-lista el dominio COMPLETO de productos.rubro (V74) mas el valor nuevo. Las filas
-- guardadas NO se tocan aca: algunas `Perfume` son juguetes o tejas mal clasificados (esta rama endurece KW_PERFUME con keywords mas
-- estrictas), asi que las alinea POST /api/ml/renormalizar con RubroResolver, no un UPDATE ciego.
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar',
                                          'deporte', 'juguetes', 'libros', 'perfumeria'));
