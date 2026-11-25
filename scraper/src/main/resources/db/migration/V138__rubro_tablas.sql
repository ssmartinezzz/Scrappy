-- V138__rubro_tablas.sql
--
-- The rubro taxonomy moves out of three CHECKs, the RubroResolver if-chain and DeflactorPorRubro into tables, with
-- ZERO behaviour change: every product keeps the rubro and the deflator index it has today.
--   rubro            the vocabulary (key, label, order), its price index, and the two weights that settle a forced site against a
--                    category: a forced site keeps the product when peso_forzado >= the category rubro's peso_categoria.
--   categoria_rubro  (categoria, sub_categoria) -> rubro, the rubro a category has on a site with no forced or default rubro.
--                    sub_categoria '' is the category as a whole. Llavero, Conjunto and Otros have no row on purpose: their rubro
--                    follows the site. pisa_forzado lets a category beat a forced site regardless of the weights (false for all today).
--   categoria.indice a price index that overrides the rubro's for one category ('TV y Audio' keeps the dollar).
-- chk_productos_rubro_domain, sitio_rubro_forzado_check and sitio_rubro_por_defecto_check become foreign keys to rubro(clave): a new
-- rubro is a row, not an ALTER. The rows are one ';'-joined literal split here, as V128-V135 do (no raw newline, no repeated literal).
-- No productos row is written, so catalog_version is not bumped and the SET CONSTRAINTS flush of V112 is not needed.
CREATE TABLE rubro (
    clave          TEXT     PRIMARY KEY,
    etiqueta       TEXT     NOT NULL,
    orden          SMALLINT NOT NULL UNIQUE,
    indice         TEXT     NOT NULL REFERENCES indice (codigo),
    peso_forzado   SMALLINT NOT NULL DEFAULT 0,
    peso_categoria SMALLINT NOT NULL DEFAULT 0
);

INSERT INTO rubro (clave, etiqueta, orden, indice, peso_forzado, peso_categoria)
SELECT split_part(f.fila, '|', 1), split_part(f.fila, '|', 2), split_part(f.fila, '|', 3)::smallint,
       split_part(f.fila, '|', 4), split_part(f.fila, '|', 5)::smallint, split_part(f.fila, '|', 6)::smallint
  FROM unnest(string_to_array('indumentaria|Indumentaria|1|IPC|0|3;tecnologia|Tecnología|2|USD_OFICIAL|2|0;suplementos|Suplementos|3|IPC|0|2;oficina|Oficina|4|IPC|1|0;construccion|Construcción|5|IPC|0|1;hogar|Hogar|6|IPC|0|1;deporte|Deporte|7|IPC|0|0;juguetes|Juguetes|8|IPC|0|0;libros|Libros|9|IPC|0|0;belleza|Belleza|10|IPC|0|1', ';')) AS f(fila);

CREATE TABLE categoria_rubro (
    categoria     TEXT    NOT NULL REFERENCES categoria (nombre),
    sub_categoria TEXT    NOT NULL DEFAULT '',
    rubro         TEXT    NOT NULL REFERENCES rubro (clave),
    pisa_forzado  BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (categoria, sub_categoria)
);

INSERT INTO categoria_rubro (categoria, sub_categoria, rubro)
SELECT split_part(f.fila, '|', 1), split_part(f.fila, '|', 2), split_part(f.fila, '|', 3)
  FROM unnest(string_to_array('Cuidado de la Piel||belleza;Cuidado del Cabello||belleza;Desodorante||belleza;Maquillaje||belleza;Perfume||belleza;Accesorio de Caño||construccion;Accesorio de Herramienta||construccion;Accesorio de Pintura||construccion;Adhesivo||construccion;Aislante||construccion;Amoladora||construccion;Bisagra||construccion;Bomba de Agua||construccion;Cable Eléctrico||construccion;Cablecanal||construccion;Caja Eléctrica||construccion;Caja de Herramientas||construccion;Canaleta||construccion;Candado||construccion;Caño||construccion;Cemento||construccion;Cerradura||construccion;Cerámico||construccion;Chapa||construccion;Cinta Adhesiva||construccion;Clavo||construccion;Césped Sintético||construccion;Diluyente||construccion;Empapelado||construccion;Enduido||construccion;Escalera||construccion;Ficha y Prolongador||construccion;Fijación||construccion;Flexible||construccion;Guante de Trabajo||construccion;Herraje||construccion;Herramienta Eléctrica||construccion;Herramienta Manual||construccion;Hidrolavadora||construccion;Hierro||construccion;Iluminación||construccion;Impermeabilizante||construccion;Ladrillo||construccion;Lija||construccion;Llave de Luz||construccion;Llave de Paso||construccion;Madera||construccion;Moldura||construccion;Mosquitero||construccion;Pastina||construccion;Perfilería||construccion;Pintura||construccion;Piso||construccion;Placa Decorativa||construccion;Placa de Yeso||construccion;Planta Artificial||construccion;Porcelanato||construccion;Portón||construccion;Protección Personal||construccion;Puerta||construccion;Regulador de Gas||construccion;Revestimiento||construccion;Sierra||construccion;Soldadora||construccion;Tablero||construccion;Taladro||construccion;Tanque de Agua||construccion;Tarugo||construccion;Toldo||construccion;Tornillo||construccion;Térmica||construccion;Ventana||construccion;Vinilo Decorativo||construccion;Wall Panel||construccion;Árido||construccion;Accesorio de Fitness||deporte;Bicicleta||deporte;Equipamiento Deportivo||deporte;Kayak y Bote||deporte;Monopatín||deporte;Paleta||deporte;Pelota||deporte;Pesas||deporte;Repuesto de Bicicleta||deporte;Accesorio de Auto||hogar;Accesorio de Baño||hogar;Accesorio de Parrilla||hogar;Accesorio de Pileta||hogar;Accesorio para Mascota||hogar;Aire Acondicionado||hogar;Alfombra||hogar;Alimento para Mascota||hogar;Almohadón||hogar;Artículo de Camping||hogar;Artículo de Limpieza||hogar;Aspiradora||hogar;Bacha||hogar;Basurero||hogar;Batería de Auto||hogar;Bazar||hogar;Bañera||hogar;Bolsa de Dormir||hogar;Caja y Canasto||hogar;Calefactor||hogar;Calefón||hogar;Cama||hogar;Cama para Mascota||hogar;Campana y Extractor||hogar;Carbón y Leña||hogar;Carpa||hogar;Cerco y Media Sombra||hogar;Cocina y Horno||hogar;Colchón||hogar;Colgador||hogar;Conservadora||hogar;Cortina||hogar;Decoración||hogar;Decoración de Jardín||hogar;Depósito de Jardín||hogar;Ducha||hogar;Escobilla de Auto||hogar;Espejo||hogar;Estantería||hogar;Funda||hogar;Gazebo y Sombrilla||hogar;Grifería||hogar;Heladera||hogar;Herramienta de Auto||hogar;Herramienta de Jardín||hogar;Higiene de Mascota||hogar;Inflable||hogar;Lavarropas||hogar;Limpieza de Auto||hogar;Lubricante||hogar;Líquido Automotor||hogar;Maceta||hogar;Mampara||hogar;Manta||hogar;Mantel||hogar;Mesa||hogar;Mueble||hogar;Mueble de Exterior||hogar;Máquina de Jardín||hogar;Parrilla||hogar;Pequeño Electrodoméstico||hogar;Pila||hogar;Pileta||hogar;Placard||hogar;Planta||hogar;Pulverizador||hogar;Químico de Pileta||hogar;Reposera y Hamaca||hogar;Respaldo||hogar;Riego||hogar;Ropa de Cama||hogar;Sanitario||hogar;Seguridad Vial||hogar;Semilla||hogar;Silla|comedor|hogar;Silla|exterior|hogar;Silla|infantil|hogar;Sillón||hogar;Sistema Modular||hogar;Tendedero||hogar;Termo y Botella||hogar;Termotanque||hogar;Tierra y Fertilizante||hogar;Toalla||hogar;Vanitory||hogar;Ventilador||hogar;Accesorio Deportivo||indumentaria;Baggy||indumentaria;Bandana||indumentaria;Bermuda||indumentaria;Bijouterie||indumentaria;Billetera||indumentaria;Bolso||indumentaria;Bombacha||indumentaria;Borcego||indumentaria;Botas||indumentaria;Botines||indumentaria;Bufanda||indumentaria;Buzo||indumentaria;Calza||indumentaria;Calzoncillos||indumentaria;Camisa||indumentaria;Camisaco||indumentaria;Campera||indumentaria;Casaca||indumentaria;Chaleco||indumentaria;Chomba||indumentaria;Cinturón||indumentaria;Corpino||indumentaria;Enterito||indumentaria;Gorra||indumentaria;Gorro||indumentaria;Guantes||indumentaria;Jean||indumentaria;Jogging||indumentaria;Lentes||indumentaria;Malla||indumentaria;Medias||indumentaria;Mocasin||indumentaria;Mochila||indumentaria;Musculosa||indumentaria;Ojotas||indumentaria;Pantalón||indumentaria;Pantufla||indumentaria;Piloto||indumentaria;Pollera||indumentaria;Puffer||indumentaria;Remera||indumentaria;Riñonera||indumentaria;Saco||indumentaria;Sandalia||indumentaria;Short||indumentaria;Sneaker||indumentaria;Sweater||indumentaria;Top||indumentaria;Top Deportivo||indumentaria;Traje||indumentaria;Traje Deportivo||indumentaria;Vestido||indumentaria;Vincha||indumentaria;Zapatilla||indumentaria;Zapatilla Entrenamiento||indumentaria;Zapatilla Running||indumentaria;Zapatilla Skate||indumentaria;Zapatilla Trekking||indumentaria;Zapatilla Urbana||indumentaria;Zapato||indumentaria;Cama Elástica||juguetes;Figura Coleccionable||juguetes;Juego de Mesa||juguetes;Juguete||juguetes;LEGO||juguetes;Peluche||juguetes;TCG||juguetes;Libro||libros;Escritorio||oficina;Mat Escritorio||oficina;Organización||oficina;Silla||oficina;Soporte Laptop||oficina;Soporte Monitor||oficina;Alimentos||suplementos;BCAA||suplementos;Barra Proteica||suplementos;Colágeno||suplementos;Creatina||suplementos;Gainer||suplementos;Magnesio||suplementos;Pancake Proteico||suplementos;Pre-Workout||suplementos;Proteína||suplementos;Proteína Isolada||suplementos;Proteína Vegetal||suplementos;Quemadores||suplementos;Snack Proteico||suplementos;Suplemento||suplementos;Vitaminas||suplementos;Accesorio Apple||tecnologia;Accesorio Gaming||tecnologia;Almacenamiento||tecnologia;Auricular||tecnologia;CPU||tecnologia;Cable||tecnologia;Celular||tecnologia;Combo de Actualización||tecnologia;Consola||tecnologia;Cooler||tecnologia;Cámara||tecnologia;Fuente||tecnologia;GPU||tecnologia;Gabinete||tecnologia;Impresión||tecnologia;Joystick||tecnologia;Micrófono||tecnologia;Mini PC||tecnologia;Monitor||tecnologia;Motherboard||tecnologia;Mouse||tecnologia;Mousepad||tecnologia;Notebook||tecnologia;PC||tecnologia;RAM||tecnologia;Red||tecnologia;Reloj||tecnologia;TV y Audio||tecnologia;Tablet||tecnologia;Teclado||tecnologia;UPS||tecnologia;Videojuego||tecnologia;Webcam||tecnologia', ';')) AS f(fila);

ALTER TABLE categoria ADD COLUMN indice TEXT REFERENCES indice (codigo);

UPDATE categoria SET indice = 'USD_OFICIAL' WHERE nombre = 'TV y Audio';

ALTER TABLE productos
    DROP CONSTRAINT chk_productos_rubro_domain,
    ADD CONSTRAINT fk_productos_rubro FOREIGN KEY (rubro) REFERENCES rubro (clave);

ALTER TABLE sitio
    DROP CONSTRAINT sitio_rubro_forzado_check,
    DROP CONSTRAINT sitio_rubro_por_defecto_check,
    ADD CONSTRAINT fk_sitio_rubro_forzado FOREIGN KEY (rubro_forzado) REFERENCES rubro (clave),
    ADD CONSTRAINT fk_sitio_rubro_por_defecto FOREIGN KEY (rubro_por_defecto) REFERENCES rubro (clave);
