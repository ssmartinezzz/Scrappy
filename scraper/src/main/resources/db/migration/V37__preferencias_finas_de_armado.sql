-- V37__preferencias_finas_de_armado.sql — pc-builder-fine-grained-prefs (fase 9)
--
-- Cuatro ejes que el armador ya decidía solo pasan a ser pedibles, y dos
-- atributos nuevos del producto pasan a leerse del nombre.
--
-- `tamanio_gabinete` es el décimo lookup del mismo molde que los de V35/V36
-- (D9): tabla con CHECK de dominio, sembrada, referenciada por FK. NO siembra
-- una fila DESCONOCIDO — un centinela de abstención es un concepto del dominio
-- Java, nunca un valor al que una foreign key pueda apuntar (la regla que V21
-- fijó con marca='').
--
-- `producto_tech_specs` suma `tamanio_gabinete_id` (nullable = el parser
-- abstuvo, o la fila no es un Gabinete) y `radiador_mm` (nullable por lo
-- mismo; el centinela de TechSpecs es 0 y esta tabla no lo repite, igual que
-- watts/capacidad_gb/velocidad_mhz de V35). El CHECK de enteros positivos se
-- reemplaza por uno que además cubre radiador_mm: un CHECK se DROPEA y se
-- vuelve a crear, nunca se edita el V35 ya aplicado, que está byte-frozen.
--
-- `preferencia_armador` suma las cuatro preferencias, todas NULLABLE = "no
-- pedida" (D1 de la fase 7). Los dos pisos llevan su propio CHECK de
-- positividad por el mismo motivo que arriba: PreferenciasDeArmado rechaza un
-- piso de 0 a propósito, y la base no puede contradecirla.
--
-- `tamanio_gabinete` NO reemplaza a `form_factor` (D1): el tamaño de torre es
-- cuánto ocupa el gabinete, el form factor es qué placa entra adentro. El veto
-- Gabinete ⊇ Mother sigue corriendo sobre form_factor, sin cambios.

CREATE TABLE tamanio_gabinete (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_tamanio_gabinete_nombre_domain
        CHECK (nombre IN ('MINI', 'MID', 'FULL'))
);
INSERT INTO tamanio_gabinete (nombre) VALUES ('MINI'), ('MID'), ('FULL');

ALTER TABLE producto_tech_specs
    ADD COLUMN tamanio_gabinete_id smallint REFERENCES tamanio_gabinete(id),
    ADD COLUMN radiador_mm         integer;

ALTER TABLE producto_tech_specs
    DROP CONSTRAINT chk_producto_tech_specs_enteros_positivos;
ALTER TABLE producto_tech_specs
    ADD CONSTRAINT chk_producto_tech_specs_enteros_positivos CHECK (
        (watts IS NULL OR watts > 0)
        AND (capacidad_gb IS NULL OR capacidad_gb > 0)
        AND (velocidad_mhz IS NULL OR velocidad_mhz > 0)
        AND (radiador_mm IS NULL OR radiador_mm > 0)
    );

ALTER TABLE preferencia_armador
    ADD COLUMN capacidad_minima_gb integer,
    ADD COLUMN tamanio_gabinete_id smallint REFERENCES tamanio_gabinete(id),
    ADD COLUMN tipo_cooler_id      smallint REFERENCES tipo_cooler(id),
    ADD COLUMN watts_minimos       integer,

    ADD CONSTRAINT chk_preferencia_armador_pisos_positivos CHECK (
        (capacidad_minima_gb IS NULL OR capacidad_minima_gb > 0)
        AND (watts_minimos IS NULL OR watts_minimos > 0)
    );
