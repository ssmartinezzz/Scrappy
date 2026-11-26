package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
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

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Base de datos")
@Feature("Rubro celulares")
@DisplayName("V140 — celulares: the SQL backfill agrees with RubroResolver and SubcategoryResolver, leaves the locked rows alone, and the rollback undoes it")
class V140RubroCelularesTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V140__rubro_celulares.sql";
    private static final String NOMBRES = "/v140/nombres-celular.txt";
    private static final String CELULARES = "celulares";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String CELULAR = "Celular";
    private static final String CARGADOR = "Cargador";
    private static final String BATERIA_EXTERNA = "Batería Externa";
    private static final String FILA_SITIO = "('%s', '%s', 'vtex', false, %s, 'config')";
    private static final String URL = "https://v140.test/";

    private static final List<String> SUBS = List.of("", "Smartphone", "Funda", "Protector", CARGADOR, "Cable", "Soporte", "Repuesto");
    private static final Map<String, String> FORZADOS = new LinkedHashMap<>();

    static {
        FORZADOS.put("v140libre", null);
        FORZADOS.put("v140tec", TECNOLOGIA);
        FORZADOS.put("v140ofi", "oficina");
        FORZADOS.put("v140hogar", "hogar");
        FORZADOS.put("v140obra", "construccion");
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("on every kind of site the stored rubro of a phone row equals the Java resolver")
    void sqlAgreesWithResolver() throws Exception {
        Map<String, Sitio> registro = new LinkedHashMap<>();
        FORZADOS.forEach((clave, forzado) -> registro.put(clave, new Sitio(clave, clave, "vtex", false, forzado, "config")));
        RubroResolver resolver = TestRubros.resolver(SiteRegistry.forTesting(registro));
        enTransaccion(st -> {
            int i = 0;
            for (String clave : FORZADOS.keySet()) {
                for (String sub : SUBS) {
                    insertar(st, URL + clave + "/" + (i++), clave, "Producto", CELULAR, sub, FORZADOS.get(clave) == null ? TECNOLOGIA : FORZADOS.get(clave), true);
                }
            }

            st.execute(migracion());

            int comparadas = 0;
            try (ResultSet rs = st.executeQuery("SELECT sitio, sub_categoria, rubro FROM productos WHERE url LIKE '" + URL + "v140%'")) {
                while (rs.next()) {
                    String clave = rs.getString("sitio");
                    assertThat(rs.getString("rubro")).as("%s / '%s'", clave, rs.getString("sub_categoria"))
                            .isEqualTo(resolver.resolver(clave, CELULAR, "", rs.getString("sub_categoria")));
                    comparadas++;
                }
            }
            assertThat(comparadas).isEqualTo(FORZADOS.size() * SUBS.size());
        });
    }

    @Test
    @DisplayName("a forced store keeps its phones, a locked row is not touched, an inactive row moves; a row of another category keeps its rubro")
    void forcedLockedAndInactive() throws Exception {
        enTransaccion(st -> {
            insertar(st, URL + "forzada", "v140tec", "Celular Samsung Galaxy A15", CELULAR, "Smartphone", TECNOLOGIA, true);
            insertar(st, URL + "inactiva", "v140libre", "Celular Moto G54", CELULAR, "Smartphone", TECNOLOGIA, false);
            insertar(st, URL + "libre", "v140libre", "Celular Moto G84", CELULAR, "Smartphone", TECNOLOGIA, true);
            insertar(st, URL + "tablet", "v140libre", "Tablet Samsung", "Tablet", "", TECNOLOGIA, true);
            insertar(st, URL + "banco-forzada", "v140tec", "Power Bank Xiaomi 10000 mAh", CELULAR, CARGADOR, TECNOLOGIA, true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueada', 'v140libre', 'Power Bank Anker 10000 mAh', 1000, 'Celular', 'Cargador', 'tecnologia', 'agent', now())");

            st.execute(migracion());

            assertThat(fila(st, "forzada")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(fila(st, "inactiva")).isEqualTo(CELULARES + "/false");
            assertThat(fila(st, "libre")).isEqualTo(CELULARES + "/true");
            assertThat(fila(st, "tablet")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(text(st, "SELECT rubro || '/' || sub_categoria FROM productos WHERE url = '" + URL + "bloqueada'"))
                    .as("locked by hand: neither its rubro nor its sub").isEqualTo(TECNOLOGIA + "/" + CARGADOR);
            assertThat(text(st, "SELECT sub_categoria FROM productos WHERE url = '" + URL + "banco-forzada'"))
                    .as("a forced store's power bank still gets the sub").isEqualTo(BATERIA_EXTERNA);
        });
    }

    @Test
    @DisplayName("the new rubro row, the category row and its price index are what the taxonomy tables say")
    void taxonomyRows() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(text(st, "SELECT clave || '|' || etiqueta || '|' || orden || '|' || indice || '|' || peso_forzado || '|' || peso_categoria FROM rubro WHERE orden = 12"))
                    .isEqualTo("celulares|Celulares|12|USD_OFICIAL|0|0");
            assertThat(text(st, "SELECT rubro FROM categoria_rubro WHERE categoria = 'Celular' AND sub_categoria = ''")).isEqualTo(CELULARES);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Celular'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Cargador rows whose name is a power bank become Batería Externa exactly where the Java resolver says so, over real names")
    void subAgreesWithResolver() throws Exception {
        SubcategoryResolver resolver = new SubcategoryResolver();
        List<String> nombres = nombres();
        assertThat(nombres).as("fixture names").hasSizeGreaterThan(300);
        enTransaccion(st -> {
            Map<String, String> esperadas = new LinkedHashMap<>();
            try (PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) "
                    + "VALUES (?, 'v140libre', ?, 1000, 'Celular', ?, 'tecnologia')")) {
                int i = 0;
                for (String nombre : nombres) {
                    String java = resolver.resolver(nombre, CELULAR);
                    String url = URL + "nombre/" + (i++);
                    esperadas.put(url, java);
                    ps.setString(1, url);
                    ps.setString(2, nombre);
                    ps.setString(3, BATERIA_EXTERNA.equals(java) ? CARGADOR : java);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            st.execute(migracion());

            List<String> distintas = new ArrayList<>();
            int baterias = 0;
            try (ResultSet rs = st.executeQuery("SELECT url, nombre, sub_categoria FROM productos WHERE url LIKE '" + URL + "nombre/%'")) {
                while (rs.next()) {
                    String java = esperadas.get(rs.getString("url"));
                    if (BATERIA_EXTERNA.equals(java)) baterias++;
                    if (!java.equals(rs.getString("sub_categoria"))) {
                        distintas.add("%s%n    java=%s  sql=%s".formatted(rs.getString("nombre"), java, rs.getString("sub_categoria")));
                    }
                }
            }
            assertThat(baterias).as("names that are a power bank (the comparison is not all-Cargador)").isGreaterThan(60);
            assertThat(distintas).as("rows where V140 and SubcategoryResolver disagree (of %d)", nombres.size()).isEmpty();
        });
    }

    @Test
    @DisplayName("a power bank is Batería Externa only where the winning sub was Cargador: the first noun of the name still wins")
    void firstNounStillWins() {
        SubcategoryResolver resolver = new SubcategoryResolver();

        assertThat(resolver.resolver("Soporte para celular y power bank", CELULAR)).isEqualTo("Soporte");
        assertThat(resolver.resolver("Smartphone Motorola Moto G54 5G 8GB 256GB batería 5000mAh", CELULAR)).isEqualTo("Smartphone");
        assertThat(resolver.resolver("Funda con batería externa para iPhone 13", CELULAR)).isEqualTo("Funda");
        assertThat(resolver.resolver("Cargador 20W USB-C para iPhone 15", CELULAR)).isEqualTo(CARGADOR);
        assertThat(resolver.resolver("Power Bank Xiaomi 10000 mAh", CELULAR)).isEqualTo(BATERIA_EXTERNA);
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                insertar(st, URL + "bump/" + i, "v140libre", "Power Bank " + i + " 10000 mAh", CELULAR, CARGADOR, TECNOLOGIA, true);
            }
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
            insertar(st, URL + "uno", "v140libre", "Power Bank Xiaomi 10000 mAh", CELULAR, CARGADOR, TECNOLOGIA, true);
            insertar(st, URL + "dos", "v140libre", "Celular Moto G54", CELULAR, "Smartphone", TECNOLOGIA, true);

            st.execute(migracion());
            st.execute(migracion());

            assertThat(text(st, "SELECT rubro || '/' || sub_categoria FROM productos WHERE url = '" + URL + "uno'")).isEqualTo(CELULARES + "/" + BATERIA_EXTERNA);
            assertThat(text(st, "SELECT rubro || '/' || sub_categoria FROM productos WHERE url = '" + URL + "dos'")).isEqualTo(CELULARES + "/Smartphone");
            assertThat(scalar(st, "SELECT count(*) FROM rubro WHERE clave = 'celulares'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the documented rollback puts the rows, the sub, the category row back, drops the rubro and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            insertar(st, URL + "telefono", "v140libre", "Celular Moto G54", CELULAR, "Smartphone", CELULARES, true);
            insertar(st, URL + "banco", "v140libre", "Power Bank Xiaomi 10000 mAh", CELULAR, BATERIA_EXTERNA, CELULARES, true);
            insertar(st, URL + "cargador", "v140libre", "Cargador 20W USB-C para iPhone", CELULAR, CARGADOR, CELULARES, true);

            st.execute(DocumentedRollback.sqlFor("V142"));
            st.execute(DocumentedRollback.sqlFor("V141"));
            st.execute(DocumentedRollback.sqlFor("V140"));

            assertThat(fila(st, "telefono")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(text(st, "SELECT sub_categoria FROM productos WHERE url = '" + URL + "banco'")).isEqualTo(CARGADOR);
            assertThat(text(st, "SELECT sub_categoria FROM productos WHERE url = '" + URL + "cargador'")).isEqualTo(CARGADOR);
            assertThat(scalar(st, "SELECT count(*) FROM rubro WHERE clave = 'celulares'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE rubro = 'celulares'")).isZero();
            assertThat(text(st, "SELECT rubro FROM categoria_rubro WHERE categoria = 'Celular' AND sub_categoria = ''")).isEqualTo(TECNOLOGIA);
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '140'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '139'")).isEqualTo(1);
        });
    }

    private static void insertar(Statement st, String url, String sitio, String nombre, String categoria, String sub, String rubro,
                                 boolean activo) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, activo) VALUES ('"
                + url + "', '" + sitio + "', '" + nombre.replace("'", "''") + "', 1000, '" + categoria + "', '" + sub + "', '" + rubro + "', " + activo + ")");
    }

    private static String fila(Statement st, String sufijo) throws SQLException {
        return text(st, "SELECT rubro || '/' || activo FROM productos WHERE url = '" + URL + sufijo + "'");
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                List<String> filas = FORZADOS.entrySet().stream()
                        .map(e -> FILA_SITIO.formatted(e.getKey(), e.getKey(), e.getValue() == null ? "NULL" : "'" + e.getValue() + "'"))
                        .toList();
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES " + String.join(", ", filas));
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
        try (InputStream in = Objects.requireNonNull(V140RubroCelularesTest.class.getResourceAsStream(NOMBRES));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String linea; (linea = r.readLine()) != null; ) {
                if (!linea.isBlank()) nombres.add(linea);
            }
        }
        return nombres;
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V140RubroCelularesTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
