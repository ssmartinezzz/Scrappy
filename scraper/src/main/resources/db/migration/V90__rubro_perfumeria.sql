-- V90__rubro_perfumeria.sql
--
-- Rubro nuevo `perfumeria` (categoria `Perfume`, que ya existia pero caia en indumentaria o en el rubro por
-- defecto del sitio). Re-lista el dominio COMPLETO de productos.rubro (V74) mas el valor nuevo.
-- POST /api/ml/renormalizar no persiste `rubro`, asi que las filas guardadas se alinean aca, sin esperar un
-- scrape por sitio. Es el gemelo SQL de RubroResolver: un sitio forzado a tecnologia/oficina conserva su rubro y una
-- fila bloqueada a mano (V3) no se toca. Ademas el nombre tiene que decir fragancia (KW_PERFUME, que ya no matchea
-- `splash` pelado ni `colonia` como subcadena): hay filas `Perfume` que son juguetes (`Base-X Splash`) o tejas
-- (`Teja Colonial`), y esas las corrige el proximo scrape, no este UPDATE. Idempotente.
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
   AND (' ' || lower(translate(p.nombre, 'áéíóúüñÁÉÍÓÚÜÑ', 'aeiouunAEIOUUN')) || ' ')
        ~ '(perfume|fragancia|eau de toilette|eau de parfum| colonia |desodorante |antitranspirante|body splash|body mist)'
   AND NOT EXISTS (SELECT 1 FROM sitio s
                    WHERE s.nombre = p.sitio AND s.rubro_forzado IN ('tecnologia', 'oficina'));
