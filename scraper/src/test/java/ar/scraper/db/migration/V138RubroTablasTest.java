package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Base de datos")
@Feature("Rubro por tablas")
@DisplayName("V138 — the rubro taxonomy lives in tables and three foreign keys replace the CHECKs")
class V138RubroTablasTest extends PostgresTestBase {

    private static final String SQLSTATE_FK = "23503";
    private static final String SQLSTATE_CHECK = "23514";
    private static final String SITIO = "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen, rubro_por_defecto)"
            + " VALUES ('V138 Test', 'v138test', 'shopify', false, %s, 'config', %s)";

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("productos.rubro rejects a rubro that is not in the table with a foreign key violation, and admits NULL and every seeded rubro")
    void productosRubroIsAForeignKey() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('nulo', 'V138 Base', 'P', 1, NULL)");
            for (String rubro : new String[] {"indumentaria", "tecnologia", "belleza"}) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('" + rubro + "', 'V138 Base', 'P', 1, '" + rubro + "')");
            }

            assertThatThrownBy(() -> st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('x', 'V138 Base', 'P', 1, 'cosmetica')"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_FK));
        });
    }

    @Test
    @DisplayName("sitio.rubro_forzado and sitio.rubro_por_defecto reject an unknown rubro, and accept a seeded one")
    void sitioColumnsAreForeignKeys() throws Exception {
        enTransaccion(st -> {
            assertThatThrownBy(() -> st.execute(SITIO.formatted("'cosmetica'", "NULL")))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_FK));
        });
        enTransaccion(st -> {
            assertThatThrownBy(() -> st.execute(SITIO.formatted("NULL", "'cosmetica'")))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_FK));
        });
        enTransaccion(st -> {
            st.execute(SITIO.formatted("'deporte'", "'libros'"));
            assertThat(scalar(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'v138test'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("seeds the ten rubros in rubros.js order (V139 appends the eleventh, V140 the twelfth), the dollar for tecnologia and celulares, and TV y Audio as the one category index")
    void seed() throws Exception {
        enTransaccion(st -> {
            assertThat(text(st, "SELECT string_agg(clave, ',' ORDER BY orden) FROM rubro"))
                    .isEqualTo("indumentaria,tecnologia,suplementos,oficina,construccion,hogar,deporte,juguetes,libros,belleza,electrodomesticos,celulares");
            assertThat(text(st, "SELECT string_agg(clave, ',' ORDER BY orden) FROM rubro WHERE indice = 'USD_OFICIAL'"))
                    .isEqualTo("tecnologia,celulares");
            assertThat(text(st, "SELECT string_agg(nombre || '=' || indice, ',') FROM categoria WHERE indice IS NOT NULL"))
                    .isEqualTo("TV y Audio=USD_OFICIAL");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE pisa_forzado")).isZero();
        });
    }

    @Test
    @DisplayName("the documented rollback restores the three CHECKs of V137 and drops the new objects and the Flyway row")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            st.execute(DocumentedRollback.sqlFor("V140"));
            st.execute(DocumentedRollback.sqlFor("V139"));
            st.execute(DocumentedRollback.sqlFor("V138"));

            assertThat(scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('rubro', 'categoria_rubro')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'categoria' AND column_name = 'indice'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM pg_constraint WHERE conname IN ('fk_productos_rubro', 'fk_sitio_rubro_forzado', 'fk_sitio_rubro_por_defecto')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM pg_constraint WHERE conname IN ('chk_productos_rubro_domain', 'sitio_rubro_forzado_check', 'sitio_rubro_por_defecto_check')"))
                    .isEqualTo(3);
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '138'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '137'")).isEqualTo(1);

            assertThatThrownBy(() -> st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('x', 'V138 Base', 'P', 1, 'cosmetica')"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_CHECK));
        });
        enTransaccion(st -> {
            st.execute(DocumentedRollback.sqlFor("V140"));
            st.execute(DocumentedRollback.sqlFor("V139"));
            st.execute(DocumentedRollback.sqlFor("V138"));
            assertThatThrownBy(() -> st.execute(SITIO.formatted("'deporte'", "NULL")))
                    .as("rubro_forzado is back to its five values")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_CHECK));
        });
        enTransaccion(st -> {
            st.execute(DocumentedRollback.sqlFor("V140"));
            st.execute(DocumentedRollback.sqlFor("V139"));
            st.execute(DocumentedRollback.sqlFor("V138"));
            st.execute(SITIO.formatted("'hogar'", "'juguetes'"));
            assertThatThrownBy(() -> st.execute("UPDATE sitio SET rubro_por_defecto = 'libros' WHERE sitio_key = 'v138test'"))
                    .as("rubro_por_defecto is back to its six values")
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_CHECK));
        });
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V138 Base', 'v138base', 'shopify', false, NULL, 'config')");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static String text(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
