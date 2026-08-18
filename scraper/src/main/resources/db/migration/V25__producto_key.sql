-- V25 — `productos.producto_key`: un handle corto y estable para la URL.
--
-- ─── QUÉ PROBLEMA RESUELVE, Y CUÁL NO ────────────────────────────────────
--
-- La vista de historial se direccionaba con la URL entera como query param:
--
--   /historial?url=https%3A%2F%2Ffullh4rd.com.ar%2Fproductos%2Fnotebook-...
--
-- Eso es ilegible, hay que encodearlo en cada borde, y mete el dominio del
-- sitio scrapeado adentro de nuestra propia ruta.
--
-- Esta columna NO es una clave primaria nueva y NO cambia la normalización.
-- `productos.url` sigue siendo la PK, y las formas normales hablan de
-- dependencias funcionales, no del tipo de la clave: `url -> producto_key` es
-- un atributo no-clave dependiendo de la clave, que es exactamente lo que 3FN
-- permite. Es un alias de presentación con un índice atrás, nada más.
--
-- ─── POR QUÉ GENERADA, IGUAL QUE `sitio_key` (V23) ───────────────────────
--
-- `GENERATED ALWAYS AS ... STORED` significa que no hay camino de escritura
-- que actualizarla ni forma de que se desincronice de `url`. Una columna
-- derivada mantenida a mano sería una copia más para desalinear — el problema
-- que `V23` ya argumentó al introducir `sitio_key`.
--
-- `md5()` y `substr()` son IMMUTABLE en Postgres, que es lo que una columna
-- generada exige. `sha256()` habría necesitado castear `url` a bytea, y ese
-- cast no es inmutable: la columna no se habría podido crear.
--
-- Esto NO es un uso criptográfico. Es un identificador opaco: sólo se le pide
-- ser determinístico y estar bien distribuido.
--
-- ─── POR QUÉ 16 CARACTERES, Y POR QUÉ UNIQUE ─────────────────────────────
--
-- 16 hex = 64 bits. Con n productos, la probabilidad de que EXISTA alguna
-- colisión es ~n²/2^65. Con 100.000 productos da ~2,7e-10; el catálogo real
-- hoy ronda los 10.000, o sea ~2,7e-12.
--
-- El UNIQUE no está para bajar esa probabilidad —no la baja— sino para elegir
-- DÓNDE falla si alguna vez pasa. Con UNIQUE, una colisión rompe fuerte y
-- temprano: acá mismo, al aplicar la migración sobre los datos que ya están.
-- Sin UNIQUE, dos productos compartirían handle y la vista mostraría el
-- producto equivocado, en silencio y para siempre. Entre un fallo ruidoso
-- improbable y un dato incorrecto callado, se elige el ruidoso.
--
-- El índice además es lo que hace que resolver key -> producto sea una
-- búsqueda y no un scan.
--
-- La MISMA expresión corre en Java (`ar.scraper.web.ProductKey.of`), porque el
-- frontend necesita el handle sin ir a la base. Que las dos no puedan divergir
-- lo prueba `ProductKeyParityTest` contra un Postgres real — mismo criterio
-- que `PrecioParser` / `sp_parse_precio_ar`, que comparten fixture.
--
-- Rollback: docs/DATABASE.md, ejecutado por V25RollbackRoundTripTest.

ALTER TABLE productos
    ADD COLUMN producto_key TEXT
    GENERATED ALWAYS AS (substr(md5(url), 1, 16)) STORED;

CREATE UNIQUE INDEX idx_productos_producto_key ON productos(producto_key);
