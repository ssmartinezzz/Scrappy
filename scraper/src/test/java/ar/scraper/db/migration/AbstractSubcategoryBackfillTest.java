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
 * What every sub_categoria backfill migration (V128, V129) must prove: the SQL and the Java resolver write the same value over a
 * fixture of real catalogue names, a locked row is not touched, an inactive row is, a row of another category keeps its sub, and the
 * documented rollback empties what the migration wrote. {@code renormalizar} rewrites the sub from Java, so any drift silently flips
 * stored values. A subclass names its migration, its fixture and one example row for each behaviour.
 */
abstract class AbstractSubcategoryBackfillTest extends PostgresTestBase {

    /** A row written with {@code categoria}, whose Java sub is {@code sub} (empty for "no sub"). */
    protected record Muestra(String nombre, String categoria, String sub) {}

    private record Fila(String url, String categoria, String nombre) {}

    /** {@code "V128"}: the documented rollback block and the URL prefix of the rows this test writes. */
    protected abstract String version();

    protected abstract String migrationPath();

    protected abstract String fixturePath();

    /** A name the migration would sub, in a category it handles. */
    protected abstract Muestra muestra();

    /** A category the migration does not handle: a row there keeps whatever sub it had. */
    protected abstract String categoriaAjena();

    @Test
    @DisplayName("every fixture name ends with the sub the Java rules give it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(500);

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
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
                assertThat(distintos).as("rows where %s and the Java rules disagree (of %d)", version(), filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("a locked row is not touched, an inactive row is, and a row of another category keeps its sub")
    void bloqueadasInactivasYAjenas() throws Exception {
        Muestra m = muestra();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "bloqueada", m, true);
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = '" + url("bloqueada") + "'");
                fila(st, "inactiva", m, false);
                fila(st, "ajena", new Muestra(m.nombre(), categoriaAjena(), ""), true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = '" + url("ajena") + "'");

                st.execute(migracion());

                assertThat(almacenado(st, url("bloqueada"))).isEmpty();
                assertThat(almacenado(st, url("inactiva"))).isEqualTo(m.sub());
                assertThat(almacenado(st, url("ajena"))).isEqualTo("queda");

                st.execute(migracion());
                assertThat(almacenado(st, url("inactiva"))).as("idempotent").isEqualTo(m.sub());
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback empties the subs it wrote and leaves any other sub alone")
    void rollback() throws Exception {
        Muestra m = muestra();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "escrita", m, true);
                fila(st, "ajena", new Muestra(m.nombre(), categoriaAjena(), ""), true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = '" + url("ajena") + "'");
                st.execute(migracion());
                assertThat(almacenado(st, url("escrita"))).isEqualTo(m.sub());

                st.execute(DocumentedRollback.sqlFor(version()));

                assertThat(almacenado(st, url("escrita"))).isEmpty();
                assertThat(almacenado(st, url("ajena"))).isEqualTo("queda");
            } finally {
                c.rollback();
            }
        }
    }

    private String url(String n) {
        return "https://" + version().toLowerCase() + ".test/" + n;
    }

    private String sitio() {
        return "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('" + version()
                + " Test', '" + version().toLowerCase() + "test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING";
    }

    private void fila(Statement st, String n, Muestra m, boolean activo) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('" + url(n) + "', '"
                + version() + " Test', '" + m.nombre() + "', 1, '" + m.categoria() + "', 'hogar', " + activo + ")");
    }

    private void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES (?, '"
                + version() + " Test', ?, 1, ?, 'hogar', true)")) {
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

    private List<Fila> fixture() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(fixturePath())) {
            assertThat(in).as("fixture %s on the classpath", fixturePath()).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linea; (linea = r.readLine()) != null; ) {
                if (linea.isBlank()) continue;
                String[] p = linea.split("\t", 2);
                filas.add(new Fila(url("agree-" + filas.size()), p[0], p[1]));
            }
        }
        return filas;
    }

    private String migracion() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(migrationPath())) {
            assertThat(in).as("migration %s on the classpath", migrationPath()).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
