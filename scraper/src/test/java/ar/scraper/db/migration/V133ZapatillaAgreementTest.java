package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V133 (SQL) and CategoryClassifier (Java) put a stored 'Zapatilla' row in the same category over real catalogue names plus edge cases.
 * renormalizar feeds the stored categoria back as raw, so any drift would silently flip moved rows back or forth.
 */
@DisplayName("V133 — el SQL y el clasificador Java coinciden sobre la Zapatilla por modelo")
class V133ZapatillaAgreementTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V133__zapatilla_por_modelo.sql";
    private static final String FIXTURE = "/v133/nombres-zapatilla.tsv";
    private static final String NOMBRE = "Zapatillas Adidas Grand Court 2.0";

    private record Fila(String url, String nombre) {}

    @Test
    @DisplayName("every fixture name ends in the category the Java classifier gives it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(500);

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                sembrar(c, filas);
                st.execute(migracion());

                CategoryClassifier java = new CategoryClassifier();
                List<String> distintos = new ArrayList<>();
                int movidas = 0;
                for (Fila f : filas) {
                    String esperada = java.normalizarCategoria("Zapatilla", f.nombre());
                    String sql = categoria(st, f.url());
                    if (!"Zapatilla".equals(esperada)) movidas++;
                    if (!esperada.equals(sql)) distintos.add("%s%n    java=%s  sql=%s".formatted(f.nombre(), esperada, sql));
                }
                assertThat(movidas).as("names that leave Zapatilla (the comparison is not all-Zapatilla)").isGreaterThan(filas.size() / 5);
                assertThat(distintos).as("rows where V133 and the Java classifier disagree (of %d)", filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("a locked row is not touched, an inactive row is, other categories and the sub_categoria stay, and a second run changes nothing")
    void bloqueadasInactivasYAjenas() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "bloqueada", NOMBRE, "Zapatilla", true);
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = '" + url("bloqueada") + "'");
                fila(st, "inactiva", NOMBRE, "Zapatilla", false);
                fila(st, "ajena", NOMBRE, "Otros", true);
                fila(st, "sub", NOMBRE, "Zapatilla", true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = '" + url("sub") + "'");

                st.execute(migracion());

                assertThat(categoria(st, url("bloqueada"))).isEqualTo("Zapatilla");
                assertThat(categoria(st, url("inactiva"))).isEqualTo("Zapatilla Urbana");
                assertThat(categoria(st, url("ajena"))).isEqualTo("Otros");
                assertThat(categoria(st, url("sub"))).isEqualTo("Zapatilla Urbana");
                try (ResultSet rs = st.executeQuery("SELECT sub_categoria FROM productos WHERE url = '" + url("sub") + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("queda");
                }

                assertThat(st.executeUpdate(migracion().replace("SET CONSTRAINTS ALL IMMEDIATE;", "")))
                        .as("a second apply rewrites nothing").isZero();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback puts the moved rows back in Zapatilla and leaves other categories alone")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "movida", NOMBRE, "Zapatilla", true);
                fila(st, "ajena", NOMBRE, "Otros", true);
                st.execute(migracion());
                assertThat(categoria(st, url("movida"))).isEqualTo("Zapatilla Urbana");

                st.execute(DocumentedRollback.sqlFor("V133"));

                assertThat(categoria(st, url("movida"))).isEqualTo("Zapatilla");
                assertThat(categoria(st, url("ajena"))).isEqualTo("Otros");
            } finally {
                c.rollback();
            }
        }
    }

    private static String url(String n) {
        return "https://v133.test/" + n;
    }

    private static String sitio() {
        return "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('V133 Test', 'v133test',"
                + " 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING";
    }

    private static void fila(Statement st, String n, String nombre, String categoria, boolean activo) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('" + url(n) + "', 'V133 Test', '"
                + nombre + "', 1, '" + categoria + "', 'indumentaria', " + activo + ")");
    }

    private static void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo)"
                + " VALUES (?, 'V133 Test', ?, 1, 'Zapatilla', 'indumentaria', true)")) {
            for (Fila f : filas) {
                ps.setString(1, f.url());
                ps.setString(2, f.nombre());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String categoria(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT categoria FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private List<Fila> fixture() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the classpath", FIXTURE).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linea; (linea = r.readLine()) != null; ) {
                if (linea.isBlank()) continue;
                filas.add(new Fila(url("agree-" + filas.size()), linea.split("\t", 2)[1]));
            }
        }
        return filas;
    }

    private String migracion() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
