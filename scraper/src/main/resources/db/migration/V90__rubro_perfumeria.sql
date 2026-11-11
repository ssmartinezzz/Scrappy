-- V90__rubro_perfumeria.sql
--
-- Rubro nuevo `perfumeria` (categoria `Perfume`, que ya existia pero caia en indumentaria o en el rubro por
-- defecto del sitio). Re-lista el dominio COMPLETO de productos.rubro (V74) mas el valor nuevo, y alinea las
-- filas guardadas con RubroResolver sin esperar a otro scrape: un sitio forzado a tecnologia/oficina conserva
-- su rubro, y una fila bloqueada a mano (V3) no se toca. Idempotente.
ALTER TABLE productos DROP CONSTRAINT chk_productos_rubro_domain;
ALTER TABLE productos
    ADD CONSTRAINT chk_productos_rubro_domain
        CHECK (rubro IS NULL OR rubro IN ('indumentaria', 'tecnologia', 'suplementos',
                                          'oficina', 'construccion', 'hogar',
                                          'deporte', 'juguetes', 'libros', 'perfumeria'));

UPDATE productos p
   SET rubro = 'perfumeria'
 WHERE p.categoria = 'Perfume'
   AND p.bloqueado_por IS NULL
   AND p.rubro IS DISTINCT FROM 'perfumeria'
   AND NOT EXISTS (SELECT 1 FROM sitio s
                    WHERE s.nombre = p.sitio AND s.rubro_forzado IN ('tecnologia', 'oficina'));
