-- V47__preferencia_armador_ram_minima_gb.sql — piso de RAM del armador de PCs
--
-- `ram_minima_gb` es el piso de GB del kit de RAM ("al menos N"), nullable =
-- "no pedida" (D1 de la fase 7). Lleva su propio CHECK de positividad por el
-- mismo motivo que `capacidad_minima_gb` en V37: PreferenciasDeArmado rechaza
-- un piso de 0 a propósito, y la base no puede contradecirla.

ALTER TABLE preferencia_armador
    ADD COLUMN ram_minima_gb integer,
    ADD CONSTRAINT chk_preferencia_armador_ram_minima_positiva CHECK (
        ram_minima_gb IS NULL OR ram_minima_gb > 0
    );
