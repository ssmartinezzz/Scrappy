CREATE TABLE sitio_categoria (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sitio_key   TEXT    NOT NULL,
    origen_id   INTEGER NOT NULL,
    padre_id    BIGINT  REFERENCES sitio_categoria(id) ON DELETE SET NULL,
    nombre      TEXT    NOT NULL,
    es_servicio BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT fk_sitio_categoria_sitio
        FOREIGN KEY (sitio_key) REFERENCES sitio(sitio_key) ON DELETE CASCADE,
    CONSTRAINT uq_sitio_categoria_origen UNIQUE (sitio_key, origen_id)
);

CREATE INDEX idx_sitio_categoria_padre ON sitio_categoria (padre_id) WHERE padre_id IS NOT NULL;

ALTER TABLE productos
    ADD COLUMN sitio_categoria_id BIGINT REFERENCES sitio_categoria(id) ON DELETE SET NULL;

CREATE INDEX idx_productos_sitio_categoria ON productos (sitio_categoria_id) WHERE sitio_categoria_id IS NOT NULL;
