ALTER TABLE sitio
    ADD COLUMN rubro_por_defecto TEXT,
    ADD CONSTRAINT sitio_rubro_por_defecto_check
        CHECK (rubro_por_defecto IS NULL
               OR rubro_por_defecto IN ('tecnologia','suplementos','oficina','construccion','hogar'));

UPDATE sitio SET rubro_por_defecto = 'construccion' WHERE sitio_key = 'easy';
