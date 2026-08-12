-- V13__categoria_lookup_table.sql — close-category-vocabulary
--
-- `categoria` pasa a tener integridad referencial. Es la primera pieza de 3FN
-- que se puede hacer: hasta V12 el vocabulario era ABIERTO (la rama del
-- breadcrumb inventaba una categoría por tienda), y una FK sobre eso degenera
-- en get-or-create por cada valor nuevo.
--
-- POR QUÉ TABLA Y NO CHECK: el proyecto ya decidió en V6 que un dominio chico
-- y cerrado va con CHECK (genero: 5 valores, rubro: 3, ml_segment: 4). Con
-- 79 valores ese criterio se da vuelta: un CHECK obliga a una MIGRACIÓN por
-- cada categoría nueva y hay que mantenerlo sincronizado a mano con
-- CategoryGroups. Con tabla, dar de alta una categoría es un INSERT.
--
-- POR QUÉ CLAVE NATURAL Y NO categoria_id: el FK va de productos.categoria
-- (TEXT) a categoria(nombre). Un id sustituto obligaría a un JOIN en cada
-- lectura del catálogo y a plomería de ids por toda la API, a cambio de nada:
-- el nombre YA es único, estable y es lo que la API devuelve. La integridad se
-- gana igual; el blast radius es cero.
--
-- LO QUE ESTA TABLA NO TIENE, A PROPÓSITO: una columna `rubro`. Parecía la
-- transitiva obvia (categoria -> rubro) pero NO existe: RubroResolver deriva
-- el rubro de (sitio, categoria, rubro previo), no de la categoría sola.
-- Verificado contra los datos vivos — `Conjunto` es `tecnologia` en Fullh4rd
-- (19 productos) y `indumentaria` en Sporting (95). Modelarlo como
-- dependencia funcional habría sido escribir una mentira en el esquema.

CREATE TABLE categoria (
    nombre TEXT PRIMARY KEY
);

INSERT INTO categoria (nombre) VALUES
    ('Accesorio Deportivo'),
    ('Alimentos'),
    ('Almacenamiento'),
    ('Auricular'),
    ('BCAA'),
    ('Baggy'),
    ('Barra Proteica'),
    ('Bermuda'),
    ('Billetera'),
    ('Bolso'),
    ('Borcego'),
    ('Botas'),
    ('Botines'),
    ('Bufanda'),
    ('Buzo'),
    ('CPU'),
    ('Calza'),
    ('Calzoncillos'),
    ('Camisa'),
    ('Campera'),
    ('Casaca'),
    ('Chaleco'),
    ('Chomba'),
    ('Cinturón'),
    ('Colágeno'),
    ('Conjunto'),
    ('Corpino'),
    ('Creatina'),
    ('Enterito'),
    ('GPU'),
    ('Gabinete'),
    ('Gainer'),
    ('Gorra'),
    ('Gorro'),
    ('Guantes'),
    ('Jean'),
    ('Jogging'),
    ('Lentes'),
    ('Magnesio'),
    ('Malla'),
    ('Medias'),
    ('Mocasin'),
    ('Mochila'),
    ('Monitor'),
    ('Mouse'),
    ('Musculosa'),
    ('Notebook'),
    ('Ojotas'),
    ('Otros'),
    ('PC'),
    ('Pancake Proteico'),
    ('Pantalón'),
    ('Pantufla'),
    ('Perfume'),
    ('Piloto'),
    ('Pollera'),
    ('Pre-Workout'),
    ('Proteína'),
    ('Puffer'),
    ('Quemadores'),
    ('RAM'),
    ('Remera'),
    ('Riñonera'),
    ('Saco'),
    ('Sandalia'),
    ('Short'),
    ('Snack Proteico'),
    ('Sneaker'),
    ('Suplemento'),
    ('Sweater'),
    ('Teclado'),
    ('Traje'),
    ('Vestido'),
    ('Vitaminas'),
    ('Webcam'),
    ('Zapatilla'),
    ('Zapatilla Entrenamiento'),
    ('Zapatilla Running'),
    ('Zapatilla Skate'),
    ('Zapatilla Urbana'),
    ('Zapato');

-- Las filas fuera del canon ya fueron remapeadas por V12; esto lo hace
-- cumplir de acá en adelante. VALID: si algo quedó afuera, la migración tiene
-- que fallar ruidosamente ahora y no dejar el dominio a medio cerrar.
ALTER TABLE productos
    ADD CONSTRAINT fk_productos_categoria
    FOREIGN KEY (categoria) REFERENCES categoria(nombre);
