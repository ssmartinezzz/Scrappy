package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mismo contrato que el resto de los {@code V*RollbackRoundTripTest}: una
 * migración aplicada es byte-frozen, así que su rollback vive en
 * {@code docs/DATABASE.md} y este test ejecuta ESE bloque exacto contra el
 * esquema real, dentro de una transacción que siempre revierte.
 *
 * <p>El rollback de {@code V25} es total y eso no es casualidad:
 * {@code producto_key} es una columna derivada de {@code url}, así que soltarla
 * no pierde ningún dato que la clave primaria no tenga ya. Es la propiedad que
 * distingue un alias de presentación de una identidad.</p>
 */
@Epic("Persistence")
@Feature("producto_key")
@Story("V25 rollback suelta la columna generada y su índice, sin pérdida de datos")
@DisplayName("V25 migration — el rollback documentado realmente corre")
class V25RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Revertir saca producto_key y su índice, y deja los productos intactos")
    void rollbackDropsTheGeneratedColumnAndItsIndex() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // fk_productos_sitio (V23) exige que el sitio exista antes que el
                // producto que lo referencia — el mismo orden que sp_upsert_run
                // resuelve con su get-or-create.
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                        + "ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                        + "VALUES ('https://site.com/v25', 'Sitio', 'P', 100, true, now(), now())");

                assertThat(columnaExiste(st, "producto_key")).isTrue();
                assertThat(indiceExiste(st, "idx_productos_producto_key")).isTrue();
                int productosAntes = contarProductos(st);

                st.execute(rollbackSql());

                assertThat(columnaExiste(st, "producto_key")).isFalse();
                assertThat(indiceExiste(st, "idx_productos_producto_key")).isFalse();
                assertThat(contarProductos(st))
                        .as("la columna era derivada: soltarla no borra ningún producto")
                        .isEqualTo(productosAntes);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("La columna se calcula sola: un INSERT no la nombra y aparece igual")
    void theColumnIsComputedByPostgresNotWritten() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // fk_productos_sitio (V23) exige que el sitio exista antes que el
                // producto que lo referencia — el mismo orden que sp_upsert_run
                // resuelve con su get-or-create.
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                        + "ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) "
                        + "VALUES ('https://site.com/generada', 'Sitio', 'P', 100, true, now(), now())");

                try (ResultSet rs = st.executeQuery(
                        "SELECT producto_key FROM productos WHERE url = 'https://site.com/generada'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1))
                            .as("16 hex derivados de la url, sin que nadie los escriba")
                            .hasSize(16)
                            .matches("[0-9a-f]{16}");
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** El bloque de rollback documentado en {@code docs/DATABASE.md}, verbatim. */
    private static String rollbackSql() {
        return DocumentedRollback.sqlFor("V25");
    }

    private static boolean columnaExiste(Statement st, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='productos' "
                        + "AND column_name='" + columna + "'")) {
            return rs.next();
        }
    }

    private static boolean indiceExiste(Statement st, String indice) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname='public' AND indexname='" + indice + "'")) {
            return rs.next();
        }
    }

    private static int contarProductos(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM productos")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
