-- V36__preferencias_de_armado.sql — pc-builder-deep-taxonomy T5
--
-- Persists the technical preferences a caller can request (D1/D7): DDR, chip
-- brand (CPU/GPU), storage technology, dual-channel RAM, wifi. Same molde as
-- V35's gama/producto_tech_specs: three more seeded lookups (marca_chip,
-- chipset_tier, tipo_cooler) plus nullable *_id columns.
--
-- `preferencia_armador` gains its own copies of these columns, all NULLABLE:
-- NULL means "not requested" (D1), same contract as `presupuesto` in V35.
-- `ram_dual`/`wifi` are NOT NULL DEFAULT false there — D2's documented
-- exception: FALSE behaves exactly like "not requested", never abstention.
--
-- `producto_tech_specs` gains `marca_chip_id`, `chipset_tier_id`,
-- `tipo_cooler_id`, `generacion`, `modulos` (all NULLABLE — NULL is
-- TechSpecsParser's abstention, D10) and `wifi` (NULLABLE here, unlike
-- preferencia_armador's: NULL means "the parser abstained / this row isn't a
-- Motherboard", and only a Motherboard row gets an affirmed true/false).
--
-- `chipset_tier`/`marca_chip` never seed a DESCONOCIDA/vacío row, same
-- reasoning as every other lookup since V21: an abstention sentinel is a
-- Java-domain concept, not a value a foreign key can point to.

CREATE TABLE marca_chip (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_marca_chip_nombre_domain
        CHECK (nombre IN ('INTEL', 'AMD', 'NVIDIA'))
);
INSERT INTO marca_chip (nombre) VALUES ('INTEL'), ('AMD'), ('NVIDIA');

CREATE TABLE chipset_tier (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_chipset_tier_nombre_domain
        CHECK (nombre IN ('X_Z', 'B', 'A_H'))
);
INSERT INTO chipset_tier (nombre) VALUES ('X_Z'), ('B'), ('A_H');

CREATE TABLE tipo_cooler (
    id     smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    CONSTRAINT chk_tipo_cooler_nombre_domain
        CHECK (nombre IN ('LIQUIDO', 'AIRE'))
);
INSERT INTO tipo_cooler (nombre) VALUES ('LIQUIDO'), ('AIRE');

ALTER TABLE preferencia_armador
    ADD COLUMN ddr_id                 smallint REFERENCES ddr(id),
    ADD COLUMN marca_cpu_id           smallint REFERENCES marca_chip(id),
    ADD COLUMN marca_gpu_id           smallint REFERENCES marca_chip(id),
    ADD COLUMN tipo_almacenamiento_id smallint REFERENCES tipo_almacenamiento(id),
    ADD COLUMN ram_dual               boolean NOT NULL DEFAULT false,
    ADD COLUMN wifi                   boolean NOT NULL DEFAULT false;

ALTER TABLE producto_tech_specs
    ADD COLUMN marca_chip_id   smallint REFERENCES marca_chip(id),
    ADD COLUMN chipset_tier_id smallint REFERENCES chipset_tier(id),
    ADD COLUMN tipo_cooler_id  smallint REFERENCES tipo_cooler(id),
    ADD COLUMN generacion      smallint,
    ADD COLUMN modulos         smallint,
    ADD COLUMN wifi            boolean;
