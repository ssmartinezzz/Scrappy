package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.db.support.PostgresTestBase;
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
@Feature("Categoría Networking")
@DisplayName("V141 — Networking: the SQL agrees with CategoryClassifier and SubcategoryResolver, moves every reference, spares locked cables, and the rollback undoes it")
class V141NetworkingAgreementTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V141__categoria_networking.sql";
    private static final String NOMBRES = "/v141/nombres-networking.tsv";
    private static final String NETWORKING = "Networking";
    private static final String RED = "Red";
    private static final String CABLE = "Cable";
    private static final String URL = "https://v141.test/";

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("over every real Red and Cable name the category and the sub the SQL stores are the ones Java computes")
    void sqlAgreesWithJava() throws Exception {
        CategoryClassifier clasificador = new CategoryClassifier();
        SubcategoryResolver subs = new SubcategoryResolver();
        List<String[]> filas = filas();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(1000);
        enTransaccion(st -> {
            Map<String, String> esperadas = new LinkedHashMap<>();
            try (PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) "
                    + "VALUES (?, 'v141', ?, 1000, ?, ?, 'tecnologia')")) {
                int i = 0;
                for (String[] f : filas) {
                    String url = URL + "nombre/" + (i++);
                    boolean eraRed = RED.equals(f[0]);
                    String categoria = eraRed || NETWORKING.equals(clasificador.normalizarCategoria("", f[1])) ? NETWORKING : CABLE;
                    esperadas.put(url, categoria + "|" + subs.resolver(f[1], categoria));
                    ps.setString(1, url);
                    ps.setString(2, f[1]);
                    ps.setString(3, f[0]);
                    ps.setString(4, eraRed ? "" : "red");
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            st.execute(migracion());

            List<String> distintas = new ArrayList<>();
            Map<String, Integer> porSub = new LinkedHashMap<>();
            int movidasDeCable = 0;
            try (ResultSet rs = st.executeQuery("SELECT url, nombre, categoria, sub_categoria FROM productos WHERE url LIKE '" + URL + "nombre/%'")) {
                while (rs.next()) {
                    String sql = rs.getString("categoria") + "|" + rs.getString("sub_categoria");
                    if (sql.startsWith(NETWORKING)) porSub.merge(rs.getString("sub_categoria"), 1, Integer::sum);
                    if (!esperadas.get(rs.getString("url")).equals(sql)) {
                        distintas.add("%s%n    java=%s  sql=%s".formatted(rs.getString("nombre"), esperadas.get(rs.getString("url")), sql));
                    }
                }
            }
            for (String[] f : filas) if (CABLE.equals(f[0]) && NETWORKING.equals(clasificador.normalizarCategoria("", f[1]))) movidasDeCable++;
            assertThat(movidasDeCable).as("Cable names the classifier sends to Networking").isGreaterThan(40);
            assertThat(porSub.keySet()).as("every sub is exercised").containsExactlyInAnyOrder("Router", "Mesh", "Access Point", "Switch", "Repetidor",
                    "Placa y Adaptador", "Cable de Red", "Conector", "");
            assertThat(distintas).as("rows where V141 and Java disagree (of %d)", filas.size()).isEmpty();
        });
    }

    @Test
    @DisplayName("every Red row, active or inactive, becomes Networking; a locked one keeps its choice, mapped to the new label; a locked Cable is not touched")
    void renamesAndSparesTheLocked() throws Exception {
        enTransaccion(st -> {
            insertar(st, "activa", "Router Tp-Link Archer C6 AC1200", RED, "router", true);
            insertar(st, "inactiva", "Switch Tenda SG108M 8 Puertos Gigabit", RED, "switch", false);
            insertar(st, "otra-categoria", "Notebook Lenovo", "Notebook", "", true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueada-red', 'v141', 'Adaptador Usb Wifi Tp-Link', 1000, 'Red', 'adaptador', 'tecnologia', 'agent', now())");
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueado-cable', 'v141', 'Cable De Red Cat6 Patch Cord Utp 2 M', 1000, 'Cable', 'red', 'tecnologia', 'agent', now())");

            st.execute(migracion());

            assertThat(par(st, "activa")).isEqualTo(NETWORKING + "/Router/true");
            assertThat(par(st, "inactiva")).isEqualTo(NETWORKING + "/Switch/false");
            assertThat(par(st, "otra-categoria")).isEqualTo("Notebook//true");
            assertThat(par(st, "bloqueada-red")).as("renamed, its choice mapped").isEqualTo(NETWORKING + "/Placa y Adaptador/true");
            assertThat(par(st, "bloqueado-cable")).as("locked by hand: neither its category nor its sub").isEqualTo(CABLE + "/red/true");
            assertThat(scalar(st, "SELECT count(*) FROM productos WHERE categoria = 'Red'")).isZero();
        });
    }

    @Test
    @DisplayName("a multiport hub that lists an RJ45 stays Cable and drops the red sub; HDMI, USB and audio cables are not touched")
    void hubsAndOtherCablesStayCable() throws Exception {
        enTransaccion(st -> {
            insertar(st, "hub", "Adaptador HUB 8 en 1 Dellorean AH8X USB-C MacBook HDMI 4K Ethernet Gigabit RJ45 SD TF PD 87W", CABLE, "red", true);
            insertar(st, "hub-usb", "Hub Genius UH-545 USB-C (CARGA) x1/ USB-A x3/ RJ45", CABLE, "red", true);
            insertar(st, "hdmi", "Cable HDMI 2.0 con Ethernet 2 metros", CABLE, "video", true);
            insertar(st, "usb", "Cable USB 3.0 A-B 1.5 m", CABLE, "usb", true);
            insertar(st, "audio", "Cable Auxiliar Jack 3.5 mm", CABLE, "", true);
            insertar(st, "cable-de-red", "Cable De Red Cat6 Patch Cord Utp 3m Gigabit", CABLE, "red", true);
            insertar(st, "adaptador", "Adaptador Usb 3.0 A Ethernet Rj45 Gigabit", CABLE, "red", true);
            insertar(st, "ficha", "Ficha Rj45 Plastica Reforzada Con Metal", CABLE, "red", true);

            st.execute(migracion());

            assertThat(par(st, "hub")).isEqualTo(CABLE + "/video/true");
            assertThat(par(st, "hub-usb")).as("no usb word on its own: the sub is empty").isEqualTo(CABLE + "//true");
            assertThat(par(st, "hdmi")).isEqualTo(CABLE + "/video/true");
            assertThat(par(st, "usb")).isEqualTo(CABLE + "/usb/true");
            assertThat(par(st, "audio")).isEqualTo(CABLE + "//true");
            assertThat(par(st, "cable-de-red")).isEqualTo(NETWORKING + "/Cable de Red/true");
            assertThat(par(st, "adaptador")).isEqualTo(NETWORKING + "/Placa y Adaptador/true");
            assertThat(par(st, "ficha")).isEqualTo(NETWORKING + "/Conector/true");
        });
    }

    @Test
    @DisplayName("sitio_categoria, categoria_stats, categoria_dismiss and saved_outfit_item follow the rename, and the old rows are gone")
    void everyReferenceMoves() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) VALUES ('v141', 1, 'Redes', 'Red'), ('v141', 2, 'Cables', 'Cable')");
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Red', now(), 7)");
            st.executeUpdate("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('Red', now()), ('Cable', now())");
            st.executeUpdate("INSERT INTO saved_outfits (nombre, total_estimado, created_at) VALUES ('v141', 0, now())");
            st.executeUpdate("INSERT INTO saved_outfit_item (outfit_id, clase, posicion, ranura, url, categoria) "
                    + "SELECT id, 'slot', 1, 'x', 'https://v141.test/item', 'Red' FROM saved_outfits WHERE nombre = 'v141'");

            st.execute(migracion());

            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v141' AND origen_id = 1")).isEqualTo(NETWORKING);
            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v141' AND origen_id = 2")).isEqualTo(CABLE);
            assertThat(text(st, "SELECT n FROM categoria_stats WHERE categoria = 'Networking'")).isEqualTo("7");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Networking'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Cable'")).isEqualTo(1);
            assertThat(text(st, "SELECT categoria FROM saved_outfit_item WHERE url = 'https://v141.test/item'")).isEqualTo(NETWORKING);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Red'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Red'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_stats WHERE categoria = 'Red'")).isZero();
        });
    }

    @Test
    @DisplayName("when Networking already has its stats row the old one is dropped, not merged into a key violation")
    void statsOfBothNames() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Red', now(), 7), ('Networking', now(), 9)");

            st.execute(migracion());

            assertThat(text(st, "SELECT n FROM categoria_stats WHERE categoria = 'Networking'")).as("the newer name wins").isEqualTo("9");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_stats WHERE categoria = 'Red'")).isZero();
        });
    }

    @Test
    @DisplayName("Networking keeps the tecnologia row of Red, and the category list has the one and not the other")
    void taxonomyRows() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(text(st, "SELECT rubro FROM categoria_rubro WHERE categoria = 'Networking' AND sub_categoria = ''")).isEqualTo("tecnologia");
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Networking'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Red'")).isZero();
        });
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                insertar(st, "bump/" + i, "Router Tp-Link Archer C" + i, RED, "router", true);
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
            insertar(st, "uno", "Router Tp-Link Archer C6 AC1200", RED, "router", true);
            insertar(st, "dos", "Cable De Red Cat6 Patch Cord Utp 3m", CABLE, "red", true);
            insertar(st, "tres", "Hub Genius UH-545 USB-C (CARGA) x1/ USB-A x3/ RJ45", CABLE, "red", true);

            st.execute(migracion());
            st.execute(migracion());

            assertThat(par(st, "uno")).isEqualTo(NETWORKING + "/Router/true");
            assertThat(par(st, "dos")).isEqualTo(NETWORKING + "/Cable de Red/true");
            assertThat(par(st, "tres")).isEqualTo(CABLE + "//true");
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Networking'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the documented rollback puts Red back with its lowercase subs, sends network cables to Cable and drops the category and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) VALUES ('v141', 1, 'Redes', 'Networking')");
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Networking', now(), 7)");
            st.executeUpdate("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('Networking', now())");
            insertar(st, "router", "Router Mesh Tp-Link Deco E4", NETWORKING, "Mesh", true);
            insertar(st, "switch", "Switch Tenda SG108M 8 Puertos Gigabit", NETWORKING, "Switch", true);
            insertar(st, "extensor", "Extensor WiFi TP-Link RE200", NETWORKING, "Repetidor", true);
            insertar(st, "placa", "Placa De Red Pci-E Tp-Link Tg-3468", NETWORKING, "Placa y Adaptador", true);
            insertar(st, "ap", "Access Point Ubiquiti AC Pro", NETWORKING, "Access Point", true);
            insertar(st, "cable", "Cable De Red Cat6 Patch Cord Utp 3m", NETWORKING, "Cable de Red", false);
            insertar(st, "ficha", "Ficha Rj45 Plastica", NETWORKING, "Conector", true);

            st.execute(DocumentedRollback.sqlFor("V141"));

            assertThat(par(st, "router")).isEqualTo(RED + "/router/true");
            assertThat(par(st, "switch")).isEqualTo(RED + "/switch/true");
            assertThat(par(st, "extensor")).isEqualTo(RED + "/repetidor/true");
            assertThat(par(st, "placa")).isEqualTo(RED + "/adaptador/true");
            assertThat(par(st, "ap")).as("no word of the old rules: blank, as before").isEqualTo(RED + "//true");
            assertThat(par(st, "cable")).isEqualTo(CABLE + "/red/false");
            assertThat(par(st, "ficha")).isEqualTo(CABLE + "/red/true");
            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v141'")).isEqualTo(RED);
            assertThat(text(st, "SELECT n FROM categoria_stats WHERE categoria = 'Red'")).isEqualTo("7");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Red'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Networking'")).isZero();
            assertThat(text(st, "SELECT rubro FROM categoria_rubro WHERE categoria = 'Red' AND sub_categoria = ''")).isEqualTo("tecnologia");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Networking'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '141'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '140'")).isEqualTo(1);
        });
    }

    private static void insertar(Statement st, String sufijo, String nombre, String categoria, String sub, boolean activo) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, activo) VALUES ('"
                + URL + sufijo + "', 'v141', '" + nombre.replace("'", "''") + "', 1000, '" + categoria + "', '" + sub + "', 'tecnologia', " + activo + ")");
    }

    private static String par(Statement st, String sufijo) throws SQLException {
        return text(st, "SELECT categoria || '/' || sub_categoria || '/' || activo FROM productos WHERE url = '" + URL + sufijo + "'");
    }

    /** The database is at the latest version, where Red is already gone: the test puts the old category back inside its own transaction. */
    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, origen) VALUES ('v141', 'v141', 'vtex', false, 'config')");
                st.executeUpdate("INSERT INTO categoria (nombre, indice) VALUES ('Red', NULL) ON CONFLICT (nombre) DO NOTHING");
                st.executeUpdate("INSERT INTO categoria_rubro (categoria, sub_categoria, rubro) VALUES ('Red', '', 'tecnologia') ON CONFLICT (categoria, sub_categoria) DO NOTHING");
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

    private static List<String[]> filas() throws IOException {
        List<String[]> filas = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(V141NetworkingAgreementTest.class.getResourceAsStream(NOMBRES));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String linea; (linea = r.readLine()) != null; ) {
                if (!linea.isBlank()) filas.add(linea.split("\t", 2));
            }
        }
        return filas;
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V141NetworkingAgreementTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
