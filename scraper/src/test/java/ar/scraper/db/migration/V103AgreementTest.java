package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V103 (SQL) and the classifier + resolver (Java) must give the same categoria and sub_categoria: renormalizar rewrites
 * sub_categoria from Java, so any drift silently flips stored values. The fixture is real names (read-only copy of the
 * catalog, 2026-11-18): every row V103 moves, plus a sample of each current sub of the hardware categories.
 */
@DisplayName("V103 — el SQL y el clasificador Java coinciden")
class V103AgreementTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V103__tecnologia_subcategoria_plataforma.sql";
    private static final String FIXTURE = "/v103/nombres-tecnologia.tsv";

    /** Categories whose sub V103 backfills. Elsewhere it only clears sport leaks, so a Mouse keeps no tier-1 sub. */
    private static final Set<String> CON_SUB_EN_SQL = Set.of("CPU", "Motherboard", "RAM", "GPU", "Auricular", "PC");

    /**
     * V103 moves a stored GPU / Auricular / Joystick to Otros when its name classifies as nothing; Java keeps the
     * stored categoria as the raw one then, so the agreement for those rows is "the name alone gives Otros".
     */
    private static final Set<String> OTROS_SI_EL_NOMBRE_NO_DICE = Set.of("GPU", "Auricular", "Joystick");

    private record Fila(String url, String raw, String nombre) {}

    @Test
    @DisplayName("Every fixture name ends with the categoria and sub the Java rules give it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(300);

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V103 Test', 'v103test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                sembrar(c, filas);
                st.execute(migracion());

                List<String> distintos = new ArrayList<>();
                CategoryClassifier clasificador = new CategoryClassifier();
                SubcategoryResolver resolver = new SubcategoryResolver();
                for (Fila f : filas) {
                    String javaCat = clasificador.normalizarCategoria(f.raw(), f.nombre(), null);
                    String javaSub = resolver.resolver(f.nombre(), javaCat);
                    String[] sql = almacenado(st, f.url());
                    boolean compararSub = CON_SUB_EN_SQL.contains(javaCat) || !javaCat.equals(f.raw());
                    boolean otrosPorFaltaDeSenal = "Otros".equals(sql[0]) && OTROS_SI_EL_NOMBRE_NO_DICE.contains(f.raw())
                            && "Otros".equals(clasificador.normalizarCategoria("", f.nombre(), null));
                    if (otrosPorFaltaDeSenal) continue;
                    if (!javaCat.equals(sql[0]) || compararSub && !javaSub.equals(sql[1])) {
                        distintos.add("%s%n    java=%s/%s  sql=%s/%s".formatted(f.nombre(), javaCat, javaSub, sql[0], sql[1]));
                    }
                }
                assertThat(distintos).as("rows where V103 and the Java rules disagree (of %d)", filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) "
                        + "VALUES (?, 'V103 Test', ?, 1, ?, 'tecnologia', true)")) {
            for (Fila f : filas) {
                ps.setString(1, f.url());
                ps.setString(2, f.nombre());
                ps.setString(3, f.raw());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String[] almacenado(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT categoria, coalesce(sub_categoria, '') FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return new String[]{rs.getString(1), rs.getString(2)};
        }
    }

    private static List<Fila> fixture() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (InputStream in = V103AgreementTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the classpath", FIXTURE).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linea; (linea = r.readLine()) != null; ) {
                if (linea.isBlank()) continue;
                String[] p = linea.split("\t", 2);
                filas.add(new Fila("v103-agree-" + filas.size(), p[0], p[1]));
            }
        }
        return filas;
    }

    private static String migracion() throws Exception {
        try (InputStream in = V103AgreementTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
