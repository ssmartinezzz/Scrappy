package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SubcategoryResolver;
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
 * V128 (SQL) and ElectroSubcategoryResolver (Java) must write the same sub_categoria: renormalizar rewrites it from Java, so any
 * drift silently flips stored values. The fixture is real names (read-only copy of the catalogue, 2026-11-20) plus the edge cases
 * of the rule: punctuation glued to the leading noun, doubled blanks, a sale prefix, an accessory that names an appliance.
 */
@DisplayName("V128 — el SQL y el resolver Java coinciden")
class V128ElectroTvAudioAgreementTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V128__subcategoria_electro_tv_audio.sql";
    private static final String FIXTURE = "/v128/nombres-electro-tv-audio.tsv";

    private static final String SITIO = "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
            + "VALUES ('V128 Test', 'v128test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING";

    private record Fila(String url, String categoria, String nombre) {}

    @Test
    @DisplayName("every fixture name ends with the sub the Java rules give it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(500);

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(SITIO);
                sembrar(c, filas);
                st.execute(migracion());

                SubcategoryResolver resolver = new SubcategoryResolver();
                List<String> distintos = new ArrayList<>();
                int conSub = 0;
                for (Fila f : filas) {
                    String java = resolver.resolver(f.nombre(), f.categoria());
                    String sql = almacenado(st, f.url());
                    if (!java.isEmpty()) conSub++;
                    if (!java.equals(sql)) distintos.add("%s [%s]%n    java=%s  sql=%s".formatted(f.nombre(), f.categoria(), java, sql));
                }
                assertThat(conSub).as("names that get a sub (the comparison is not all-empty)").isGreaterThan(filas.size() / 2);
                assertThat(distintos).as("rows where V128 and the Java rules disagree (of %d)", filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("a locked row is not touched, an inactive row is, and a row of another category keeps its sub")
    void bloqueadasInactivasYAjenas() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(SITIO);
                fila(st, "bloqueada", "Cafetera Oster Primalatte", "Pequeño Electrodoméstico", true);
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = 'https://v128.test/bloqueada'");
                fila(st, "inactiva", "Parlante Portatil Noblex", "TV y Audio", false);
                fila(st, "ajena", "Cafetera Para Auto", "Otros", true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = 'https://v128.test/ajena'");

                st.execute(migracion());

                assertThat(almacenado(st, "https://v128.test/bloqueada")).isEmpty();
                assertThat(almacenado(st, "https://v128.test/inactiva")).isEqualTo("Parlante");
                assertThat(almacenado(st, "https://v128.test/ajena")).isEqualTo("queda");

                st.execute(migracion());
                assertThat(almacenado(st, "https://v128.test/inactiva")).as("idempotent").isEqualTo("Parlante");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback empties the subs it wrote and leaves any other sub alone")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(SITIO);
                fila(st, "cafetera", "Cafetera Oster Primalatte", "Pequeño Electrodoméstico", true);
                fila(st, "parlante", "Parlante Portatil Noblex", "TV y Audio", true);
                fila(st, "ajena", "Cafetera Para Auto", "Otros", true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = 'https://v128.test/ajena'");
                st.execute(migracion());
                assertThat(almacenado(st, "https://v128.test/cafetera")).isEqualTo("Cafetera");

                st.execute(DocumentedRollback.sqlFor("V128"));

                assertThat(almacenado(st, "https://v128.test/cafetera")).isEmpty();
                assertThat(almacenado(st, "https://v128.test/parlante")).isEmpty();
                assertThat(almacenado(st, "https://v128.test/ajena")).isEqualTo("queda");
            } finally {
                c.rollback();
            }
        }
    }

    private static void fila(Statement st, String n, String nombre, String categoria, boolean activo) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('https://v128.test/" + n
                + "', 'V128 Test', '" + nombre + "', 1, '" + categoria + "', 'tecnologia', " + activo + ")");
    }

    private static void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES (?, 'V128 Test', ?, 1, ?, 'tecnologia', true)")) {
            for (Fila f : filas) {
                ps.setString(1, f.url());
                ps.setString(2, f.nombre());
                ps.setString(3, f.categoria());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String almacenado(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT coalesce(sub_categoria, '') FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static List<Fila> fixture() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (InputStream in = V128ElectroTvAudioAgreementTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the classpath", FIXTURE).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linea; (linea = r.readLine()) != null; ) {
                if (linea.isBlank()) continue;
                String[] p = linea.split("\t", 2);
                filas.add(new Fila("https://v128.test/agree-" + filas.size(), p[0], p[1]));
            }
        }
        return filas;
    }

    private static String migracion() throws Exception {
        try (InputStream in = V128ElectroTvAudioAgreementTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
