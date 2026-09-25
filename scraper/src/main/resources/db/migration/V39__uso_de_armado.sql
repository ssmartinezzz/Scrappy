-- V39__uso_de_armado.sql — pc-builder-homelab, T6
--
-- Un lookup más, mismo molde que los diez de V35/V36/V37: smallint identity +
-- nombre UNIQUE + un CHECK de dominio. Respalda el perfil de uso pedible del
-- armador (D3/D7 en odd/tasks/pc-builder-homelab.md): GAMING (el armado de
-- hoy, default) y HOMELAB (fase 10).
--
-- `uso` siembra LAS DOS filas — a diferencia de los diez lookups anteriores,
-- que nunca siembran su centinela de abstención (D10, V35): acá GAMING NO es
-- un centinela, es un valor pedible como cualquier otro, con su propia fila
-- normalizada, igual que las tres de `gama`. `uso_id` es NULLABLE porque toda
-- fila de `preferencia_armador` de ANTES de esta migración no tiene uso
-- guardado — eso es abstención real ("nunca se pidió"), y NULL es lo que le
-- corresponde por D10, nunca una fila sentinela. Una vez guardada, una
-- preferencia SIEMPRE apunta a una fila concreta (GAMING o HOMELAB): el
-- write path (`UsoMapeo`, en db/, mismo molde que `GamaMapeo`) nunca vuelve
-- a escribir NULL a propósito — sólo lo hereda de filas viejas hasta que se
-- resguarden de nuevo. NULL se sigue LEYENDO como GAMING (mismo default de
-- siempre), pero no porque GAMING viva ahí: porque "no elegido todavía" y
-- "eligió gaming" son observables iguales para quien arma hoy.
--
-- 1FN/3FN: `uso` no tiene grupo repetitivo, y `uso_id` depende de la clave
-- completa de `preferencia_armador` (`id`), no de una parte ni de otro
-- atributo no-clave — misma normalización que `gama` y los diez lookups
-- anteriores (lookup + FK, nunca un TEXT con su propio CHECK repetido, nunca
-- un booleano tipo `es_homelab` que duplicaría el mismo hecho en otra forma).

CREATE TABLE uso (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_uso_nombre_domain
        CHECK (nombre IN ('GAMING', 'HOMELAB'))
);
INSERT INTO uso (nombre) VALUES ('GAMING'), ('HOMELAB');

ALTER TABLE preferencia_armador
    ADD COLUMN uso_id smallint REFERENCES uso(id);
