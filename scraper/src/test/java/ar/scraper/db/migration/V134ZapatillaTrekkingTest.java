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
 * V134 (SQL) and CategoryClassifier / SubcategoryResolver (Java) agree on a stored shoe row over real catalogue names plus edge cases:
 * the category (Zapatilla Trekking or untouched) and the new tier-2 sport subs (básquet, handball, golf).
 */
@DisplayName("V134 — el SQL y el clasificador Java coinciden sobre Zapatilla Trekking y las subs de deporte")
class V134ZapatillaTrekkingTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V134__zapatilla_trekking.sql";
    private static final String FIXTURE = "/v134/nombres-zapatilla-trekking.tsv";
    private static final String NOMBRE = "Zapatillas Salomon X Ultra 360 Mid Gtx";
    private static final String TREKKING = "Zapatilla Trekking";
    private static final Set<String> ORIGEN = Set.of("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento", "Zapatilla Skate", "Zapatilla Urbana");
    private static final Set<String> SUBS_NUEVAS = Set.of("básquet", "handball", "golf");

    private record Fila(String url, String categoria, String nombre) {}

    @Test
    @DisplayName("every fixture name ends in the category and the sub the Java code gives it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(500);

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                sembrar(c, filas);
                st.execute(migracion());

                CategoryClassifier categorias = new CategoryClassifier();
                SubcategoryResolver subs = new SubcategoryResolver();
                List<String> distintos = new ArrayList<>();
                int movidas = 0;
                int conSub = 0;
                for (Fila f : filas) {
                    boolean mueve = ORIGEN.contains(f.categoria()) && TREKKING.equals(categorias.normalizarCategoria(f.categoria(), f.nombre()));
                    String categoriaEsperada = mueve ? TREKKING : f.categoria();
                    String subJava = subs.resolver(f.nombre(), categoriaEsperada);
                    String subEsperada = SUBS_NUEVAS.contains(subJava) ? subJava : "";
                    String[] sql = estado(st, f.url());
                    if (mueve) movidas++;
                    if (!subEsperada.isEmpty()) conSub++;
                    if (!categoriaEsperada.equals(sql[0]) || !subEsperada.equals(sql[1])) {
                        distintos.add("%s [%s]%n    java=%s/%s  sql=%s/%s".formatted(f.nombre(), f.categoria(), categoriaEsperada, subEsperada, sql[0], sql[1]));
                    }
                }
                assertThat(movidas).as("names that move to Zapatilla Trekking (the comparison is not all-unchanged)").isGreaterThan(50);
                assertThat(conSub).as("names that get a new sport sub").isGreaterThan(30);
                assertThat(distintos).as("rows where V134 and the Java code disagree (of %d)", filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("a locked row is not touched, an inactive row is, other categories stay, the sub is only filled when empty, and a second run changes nothing")
    void bloqueadasInactivasYAjenas() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "bloqueada", NOMBRE, "Zapatilla", true);
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = '" + url("bloqueada") + "'");
                fila(st, "inactiva", NOMBRE, "Zapatilla", false);
                fila(st, "ajena", NOMBRE, "Otros", true);
                fila(st, "running", NOMBRE, "Zapatilla Running", true);
                fila(st, "sub", "Zapatillas Topper Basquet", "Zapatilla", true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'queda' WHERE url = '" + url("sub") + "'");
                fila(st, "vacia", "Zapatillas Topper Basquet", "Zapatilla", true);
                fila(st, "ropa", "Remera Basquet Chicago", "Remera", true);

                st.execute(migracion());

                assertThat(estado(st, url("bloqueada"))[0]).isEqualTo("Zapatilla");
                assertThat(estado(st, url("inactiva"))[0]).isEqualTo(TREKKING);
                assertThat(estado(st, url("ajena"))[0]).isEqualTo("Otros");
                assertThat(estado(st, url("running"))[0]).isEqualTo(TREKKING);
                assertThat(estado(st, url("sub"))[1]).isEqualTo("queda");
                assertThat(estado(st, url("vacia"))[1]).isEqualTo("básquet");
                assertThat(estado(st, url("ropa"))[1]).as("the sub backfill is limited to the shoe categories").isEmpty();

                String desdeMovimientos = migracion().substring(migracion().indexOf("SET CONSTRAINTS ALL IMMEDIATE;") + "SET CONSTRAINTS ALL IMMEDIATE;".length());
                int subs = desdeMovimientos.indexOf("-- 3.");
                assertThat(st.executeUpdate(desdeMovimientos.substring(0, subs))).as("a second apply moves nothing").isZero();
                assertThat(st.executeUpdate(desdeMovimientos.substring(subs))).as("a second apply fills no sub").isZero();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback empties the new subs, puts the moved rows back in Zapatilla and drops the category")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "movida", NOMBRE, "Zapatilla", true);
                fila(st, "basquet", "Zapatillas Topper Basquet", "Zapatilla Urbana", true);
                fila(st, "basket", "Zapatillas Topper Basket", "Zapatilla Urbana", true);
                st.executeUpdate("UPDATE productos SET sub_categoria = 'básquet' WHERE url = '" + url("basket") + "'");
                fila(st, "ajena", NOMBRE, "Otros", true);
                st.execute(migracion());
                assertThat(estado(st, url("movida"))[0]).isEqualTo(TREKKING);
                assertThat(estado(st, url("basquet"))[1]).isEqualTo("básquet");

                st.execute(DocumentedRollback.sqlFor("V140"));
                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.execute(DocumentedRollback.sqlFor("V134"));

                assertThat(estado(st, url("movida"))[0]).isEqualTo("Zapatilla");
                assertThat(estado(st, url("basquet"))[1]).as("a sub that came from 'basquet' goes").isEmpty();
                assertThat(estado(st, url("basket"))[1]).as("a sub the old words already gave stays").isEqualTo("básquet");
                assertThat(estado(st, url("ajena"))[0]).isEqualTo("Otros");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM categoria WHERE nombre = '" + TREKKING + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static String url(String n) {
        return "https://v134.test/" + n;
    }

    private static String sitio() {
        return "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('V134 Test', 'v134test',"
                + " 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING";
    }

    private static void fila(Statement st, String n, String nombre, String categoria, boolean activo) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('" + url(n) + "', 'V134 Test', '"
                + nombre + "', 1, '" + categoria + "', 'indumentaria', " + activo + ")");
    }

    private static void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo)"
                + " VALUES (?, 'V134 Test', ?, 1, ?, 'indumentaria', true)")) {
            for (Fila f : filas) {
                ps.setString(1, f.url());
                ps.setString(2, f.nombre());
                ps.setString(3, f.categoria());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String[] estado(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT categoria, COALESCE(sub_categoria, '') FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return new String[] {rs.getString(1), rs.getString(2)};
        }
    }

    private List<Fila> fixture() throws Exception {
        List<Fila> filas = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the classpath", FIXTURE).isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String linea; (linea = r.readLine()) != null; ) {
                if (linea.isBlank()) continue;
                String[] partes = linea.split("\t", 2);
                filas.add(new Fila(url("agree-" + filas.size()), partes[0], partes[1]));
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
