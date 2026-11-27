package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.testsupport.TestRubros;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Base de datos")
@Feature("Subs de Reloj")
@DisplayName("V143 — Reloj malla y analógico: the SQL agrees with SubcategoryResolver and RubroResolver, spares what it must, and the rollback undoes it")
class V143RelojAgreementTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V143__reloj_malla_y_analogico.sql";
    private static final String NOMBRES = "/v143/nombres-reloj.txt";
    private static final String RELOJ = "Reloj";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String INDUMENTARIA = "indumentaria";
    private static final String URL = "https://v143.test/";

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("over every real Reloj name the sub and rubro the SQL stores are the ones Java computes")
    void sqlAgreesWithJava() throws Exception {
        SubcategoryResolver subs = new SubcategoryResolver();
        RubroResolver rubros = rubroEnTiendaLibre();
        List<String> nombres = nombres();
        assertThat(nombres).as("fixture rows").hasSizeGreaterThan(1000);
        enTransaccion(st -> {
            Map<String, String> esperadas = new LinkedHashMap<>();
            try (PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) "
                    + "VALUES (?, 'v143', ?, 1000, 'Reloj', ?, ?)")) {
                int i = 0;
                for (String nombre : nombres) {
                    String url = URL + "nombre/" + (i++);
                    String sub = subs.resolver(nombre, RELOJ);
                    // What was stored before V143: the keyword tier's answer, or blank (the two new subs did not exist).
                    String guardada = "smartwatch".equals(sub) || "deportivo".equals(sub) ? sub : "";
                    esperadas.put(url, sub + "|" + rubros.resolver("v143", RELOJ, TECNOLOGIA, sub));
                    ps.setString(1, url);
                    ps.setString(2, nombre);
                    ps.setString(3, guardada);
                    ps.setString(4, TECNOLOGIA);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            st.execute(migracion());

            List<String> distintas = new ArrayList<>();
            Map<String, Integer> porSub = new TreeMap<>();
            try (ResultSet rs = st.executeQuery("SELECT url, nombre, sub_categoria, rubro FROM productos WHERE url LIKE '" + URL + "nombre/%'")) {
                while (rs.next()) {
                    String sql = rs.getString("sub_categoria") + "|" + rs.getString("rubro");
                    porSub.merge(rs.getString("sub_categoria"), 1, Integer::sum);
                    if (!esperadas.get(rs.getString("url")).equals(sql)) {
                        distintas.add("%s%n    java=%s  sql=%s".formatted(rs.getString("nombre"), esperadas.get(rs.getString("url")), sql));
                    }
                }
            }
            assertThat(porSub.keySet()).as("every sub is exercised").contains("malla", "analógico", "smartwatch", "deportivo", "");
            assertThat(distintas).as("rows where V143 and Java disagree (of %d)", nombres.size()).isEmpty();
        });
    }

    @Test
    @DisplayName("a strap beats the smartwatch sub it had; a plain watch with a blank sub takes analógico and indumentaria; smartwatch and deportivo are kept")
    void subsAndRubro() throws Exception {
        enTransaccion(st -> {
            insertar(st, "malla", "Malla Para Reloj Xiaomi Mi Smart Band 8 9 10 Botón Acero Bordo", "smartwatch", "v143", true);
            insertar(st, "correa", "Correa de Titanio", "", "v143", true);
            insertar(st, "pulsera", "Pulsera Ocean para Apple Watch Ultra Amarillo 49-45-44-42-41-40mm", "", "v143", true);
            insertar(st, "casio", "RELOJ CASIO F-91W-1D", "", "v143", true);
            insertar(st, "gadnic", "Reloj Analógico Gadnic RELF1 Pulsera Malla Correa Simil Cuero Negro y Dorado", "", "v143", false);
            insertar(st, "smart", "Reloj Smartwatch Garmin Forerunner 70 - 42 mm", "smartwatch", "v143", true);
            insertar(st, "deportivo", "Reloj Casio Deportivo Digital Ws-1400h-8bvcf Gris Oscuro Unisex", "deportivo", "v143", true);
            insertar(st, "garmin", "Reloj Garmin Forerunner 170", "", "v143", true);
            insertar(st, "radio", "Radio Reloj JBL Despert.Horizon 3 Bt Luz", "", "v143", true);
            insertar(st, "comparador", "Reloj Comparador Profesional De 0 A 10mm Acero Cr-v Lusqtoff Digital", "", "v143", true);
            insertar(st, "otra", "Malla Elastica Hamaca", "", "v143", true);
            st.executeUpdate("UPDATE productos SET categoria = 'Cámara' WHERE url = '" + URL + "otra'");
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueado', 'v143', 'Malla Para Reloj Samsung Watch 7', 1000, 'Reloj', 'a mano', 'tecnologia', 'agent', now())");

            st.execute(migracion());

            assertThat(fila(st, "malla")).isEqualTo("malla/tecnologia/true");
            assertThat(fila(st, "correa")).isEqualTo("malla/tecnologia/true");
            assertThat(fila(st, "pulsera")).isEqualTo("malla/tecnologia/true");
            assertThat(fila(st, "casio")).isEqualTo("analógico/indumentaria/true");
            assertThat(fila(st, "gadnic")).as("inactive rows are rewritten too").isEqualTo("analógico/indumentaria/false");
            assertThat(fila(st, "smart")).isEqualTo("smartwatch/tecnologia/true");
            assertThat(fila(st, "deportivo")).isEqualTo("deportivo/tecnologia/true");
            assertThat(fila(st, "garmin")).as("a smart watch that leads with 'reloj' stays blank").isEqualTo("/tecnologia/true");
            assertThat(fila(st, "radio")).isEqualTo("/tecnologia/true");
            assertThat(fila(st, "comparador")).as("a tool that shares the noun").isEqualTo("/tecnologia/true");
            assertThat(fila(st, "otra")).as("another category is not read").isEqualTo("/tecnologia/true");
            assertThat(fila(st, "bloqueado")).as("locked by hand: not touched").isEqualTo("a mano/tecnologia/true");
        });
    }

    @Test
    @DisplayName("a watch on a store forced to a rubro is analógico but keeps the rubro of the store")
    void forcedSiteKeepsItsRubro() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v143f', 'v143f', 'vtex', false, 'tecnologia', 'config')");
            insertar(st, "forzado", "Reloj Casio Digital Ae-1200wh-5avcf Mens Negro y dorado", "", "v143f", true);

            st.execute(migracion());

            assertThat(fila(st, "forzado")).isEqualTo("analógico/tecnologia/true");
        });
    }

    @Test
    @DisplayName("the taxonomy row: ('Reloj', 'analógico') is indumentaria without pisa_forzado, and the category itself stays tecnologia")
    void taxonomyRow() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Reloj' AND sub_categoria = 'analógico'")).isEqualTo("indumentaria/false");
            assertThat(text(st, "SELECT rubro FROM categoria_rubro WHERE categoria = 'Reloj' AND sub_categoria = ''")).isEqualTo(TECNOLOGIA);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Reloj'")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                insertar(st, "bump/" + i, "Malla Para Reloj Xiaomi Mi Band " + i, "", "v143", true);
            }
            insertar(st, "bump/casio", "Reloj Casio Digital Gadnic", "", "v143", true);
            st.execute("SET CONSTRAINTS ALL IMMEDIATE");
            st.execute("SELECT set_config('catalog_version.bumped_txid', '', true)");
            long antes = scalar(st, "SELECT version FROM catalog_version");
            st.execute("SET CONSTRAINTS ALL DEFERRED");

            st.execute(migracion());
            st.execute("SET CONSTRAINTS ALL IMMEDIATE");

            assertThat(scalar(st, "SELECT version FROM catalog_version")).isEqualTo(antes + 1);
        });
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void rerunnable() throws Exception {
        enTransaccion(st -> {
            insertar(st, "uno", "Malla Para Reloj Xiaomi Mi Band 5 6 7 Negro", "smartwatch", "v143", true);
            insertar(st, "dos", "RELOJ CASIO F-91W-1D", "", "v143", true);

            st.execute(migracion());
            st.execute(migracion());

            assertThat(fila(st, "uno")).isEqualTo("malla/tecnologia/true");
            assertThat(fila(st, "dos")).isEqualTo("analógico/indumentaria/true");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Reloj' AND sub_categoria = 'analógico'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the documented rollback blanks analógico and returns its rows to tecnologia, gives the straps their keyword sub back, and drops the taxonomy row and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v143f', 'v143f', 'vtex', false, 'oficina', 'config')");
            insertar(st, "casio", "RELOJ CASIO F-91W-1D", "analógico", "v143", true);
            st.executeUpdate("UPDATE productos SET rubro = 'indumentaria' WHERE url = '" + URL + "casio'");
            insertar(st, "forzado", "Reloj Casio Digital Ae-1200wh-5avcf", "analógico", "v143f", true);
            st.executeUpdate("UPDATE productos SET rubro = 'oficina' WHERE url = '" + URL + "forzado'");
            insertar(st, "malla-smart", "Malla Para Reloj Xiaomi Mi Smart Band 8", "malla", "v143", true);
            insertar(st, "malla-gps", "Correa Garmin Forerunner Gps 20mm", "malla", "v143", true);
            insertar(st, "malla-nada", "Malla Para Reloj Redmi Watch 4", "malla", "v143", true);

            st.execute(DocumentedRollback.sqlFor("V144"));
            st.execute(DocumentedRollback.sqlFor("V143"));

            assertThat(fila(st, "casio")).isEqualTo("/tecnologia/true");
            assertThat(fila(st, "forzado")).as("a forced site's rubro is not ours to restore").isEqualTo("/oficina/true");
            assertThat(fila(st, "malla-smart")).isEqualTo("smartwatch/tecnologia/true");
            assertThat(fila(st, "malla-gps")).isEqualTo("deportivo/tecnologia/true");
            assertThat(fila(st, "malla-nada")).isEqualTo("/tecnologia/true");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Reloj' AND sub_categoria = 'analógico'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '143'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '142'")).isEqualTo(1);
        });
    }

    private static void insertar(Statement st, String sufijo, String nombre, String sub, String sitio, boolean activo) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, activo) VALUES ('"
                + URL + sufijo + "', '" + sitio + "', '" + nombre.replace("'", "''") + "', 1000, 'Reloj', '" + sub + "', 'tecnologia', " + activo + ")");
    }

    private static String fila(Statement st, String sufijo) throws SQLException {
        return text(st, "SELECT sub_categoria || '/' || rubro || '/' || activo FROM productos WHERE url = '" + URL + sufijo + "'");
    }

    /** A store with no forced rubro: where the sub rule of categoria_rubro decides. */
    private static RubroResolver rubroEnTiendaLibre() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of("v143", new SiteRegistry.Sitio(
                "v143", "v143", "vtex", false, null, "config", false, null)));
        return TestRubros.resolver(registry);
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v143', 'v143', 'vtex', false, NULL, 'config')");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static String text(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> nombres() throws IOException {
        List<String> nombres = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(V143RelojAgreementTest.class.getResourceAsStream(NOMBRES));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String linea; (linea = r.readLine()) != null; ) {
                if (!linea.isBlank()) nombres.add(linea);
            }
        }
        return nombres;
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V143RelojAgreementTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
