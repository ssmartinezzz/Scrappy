package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.CategoryGroups;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V135 (SQL) and CategoryClassifier / SubcategoryResolver (Java) agree on a stored row over real catalogue names: the category the
 * migration moves it to (Accesorio Gaming, Consola, Videojuego, Joystick, Gorra, Otros) or leaves it in, and the platform sub.
 * Only the transitions V135 owns are compared; a stored Videojuego that an older Java rule would reshuffle on the next
 * renormalizar is outside it (the test counts those instead of hiding them).
 */
@DisplayName("V135 — el SQL y el clasificador Java coinciden sobre Accesorio Gaming y las subs de Consola, Videojuego y Joystick")
class V135GamingAgreementTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V135__accesorio_gaming_y_subs_de_consola.sql";
    private static final String FIXTURE = "/v135/nombres-gaming.tsv";
    private static final String ACCESORIO = "Accesorio Gaming";
    private static final Set<String> ORIGEN = Set.of("Otros", "Consola", "Videojuego", "Joystick", "Cable", "Almacenamiento", "Cooler",
            "Celular", "Auricular", "Webcam", "Red", "Fuente", "Organización", "Bolso", "Zapatilla Skate");
    private static final Set<String> GAMING = Set.of("Consola", "Videojuego", "Joystick");
    private static final Pattern FUERA = Pattern.compile("(?i)\\b(?:gorra|snapback|bottle|botella|pedalera de efectos|pedalera de multi efectos|footswitch)\\b");

    private record Fila(String url, String categoria, String nombre) {}

    private static final String[][] EXTRA = {
        {"Remera", "Remera Nike Nsw Club Mujer"},
        {"Buzo", "Buzo Nike W Nsw Club Flc Std Mujer"},
        {"Red", "Switch Cisco CBS220 4GB 4X1G SFP"},
        {"Red", "Switch HP 1960 24G 2XT 2XF 370W"},
        {"Teclado", "Teclado Mecanico Aula AG60 Pro Case Black Frosted Keycaps Switch Aether"},
        {"Escritorio", "Escritorio Gamer Mesa Pc Playstation Xbox Tables 3047 Color Negro Filos Azules"},
        {"Impresión", "Impresora 3D Bambulab Ps2 Combo"},
        {"Otros", "Adaptador Hdmi Switch 3 Entradas 1 Salida"},
        {"Cable", "Cable De Red 15 Metros Cat.5e Rj45 Smart Tv Modem Router Ps4"},
        {"Otros", "Carrera First Nintendo Mario Kart Slot Car Race Track - Includes 2 Cars - Battery-Powered Beginner Toy Racing Set"},
        {"Joystick", "Joystick Cooler Master Storm Controller Xbox One/Series/PC"},
        {"Videojuego", "Mario Tennis Aces - Nintendo Switch"},
        {"Videojuego", "Spirit Camera - Nintendo 3DS"},
        {"Videojuego", "Pikmin 3 - Wii U (Original Cover)"},
        {"Otros", "Switch Joy-Con (L/R) - AA Battery Pack"},
        {"Joystick", "Nintendo Switch 2 Joy-Con 2 Charging Grip - Nintendo"},
        {"Otros", "PowerA Enhanced Wired Controller and Slim Case for Nintendo Switch - Fire Mario"},
        {"Otros", "GameCube Controller Adapter for SWITCH (Original NINTENDO)"},
        {"Otros", "Super Mario Party + Pastel Purple Joy-Con Bundle (Nintendo Switch)"},
        {"Consola", "Nintendo Switch 2 + Mario Kart World Bundle"},
    };

    @Test
    @DisplayName("every fixture name ends in the category and the sub the Java code gives it")
    void coinciden() throws Exception {
        List<Fila> filas = fixture();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(1000);

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
                int aAccesorio = 0;
                int fuera = 0;
                int conSub = 0;
                int fueraDeV135 = 0;
                for (Fila f : filas) {
                    String java = categorias.normalizarCategoria(f.categoria(), f.nombre());
                    String esperada = f.categoria();
                    if (!java.equals(f.categoria())) {
                        if (ORIGEN.contains(f.categoria()) && ACCESORIO.equals(java)) esperada = java;
                        else if (Set.of("Otros", "Videojuego", "Joystick").contains(f.categoria()) && "Consola".equals(java)) esperada = java;
                        else if (Set.of("Otros", "Videojuego", "Fuente", "Bolso").contains(f.categoria()) && "Joystick".equals(java)) esperada = java;
                        else if ("Otros".equals(f.categoria()) && "Videojuego".equals(java)) esperada = java;
                        else if (GAMING.contains(f.categoria()) && FUERA.matcher(f.nombre()).find()) {
                            String sinRaw = categorias.normalizarCategoria("", f.nombre());
                            if (Set.of("Gorra", "Otros").contains(sinRaw)) esperada = sinRaw;
                        }
                        if (esperada.equals(f.categoria())) fueraDeV135++;
                    }
                    String subEsperada = GAMING.contains(esperada) ? subs.resolver(f.nombre(), esperada) : "";
                    String[] sql = estado(st, f.url());
                    if (!esperada.equals(f.categoria())) movidas++;
                    if (ACCESORIO.equals(esperada) && !esperada.equals(f.categoria())) aAccesorio++;
                    if (FUERA.matcher(f.nombre()).find() && !esperada.equals(f.categoria())) fuera++;
                    if (!subEsperada.isEmpty()) conSub++;
                    if (!esperada.equals(sql[0]) || !subEsperada.equals(sql[1])) {
                        distintos.add("%s [%s]%n    java=%s/%s  sql=%s/%s".formatted(f.nombre(), f.categoria(), esperada, subEsperada, sql[0], sql[1]));
                    }
                }
                assertThat(movidas).as("names that move (the comparison is not all-unchanged)").isGreaterThan(250);
                assertThat(aAccesorio).as("names that move to Accesorio Gaming").isGreaterThan(150);
                assertThat(fuera).as("non-gaming names that leave a gaming category").isGreaterThanOrEqualTo(2);
                assertThat(conSub).as("names that get a platform sub").isGreaterThan(500);
                assertThat(fueraDeV135).as("rows an older Java rule would reshuffle on renormalizar, outside V135").isLessThan(15);
                assertThat(distintos).as("rows where V135 and the Java code disagree (of %d)", filas.size()).isEmpty();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("a locked row is not touched, an inactive row is, the rubro follows the site, and a second run changes nothing")
    void bloqueadasInactivasRubroEIdempotencia() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('V135 Forzado', 'v135forzado',"
                        + " 'shopify', false, 'tecnologia', 'config') ON CONFLICT DO NOTHING");
                String caso = "Funda Para Nintendo Switch Oled";
                fila(st, "bloqueada", caso, "Otros", "juguetes", true, "V135 Test");
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = '" + url("bloqueada") + "'");
                fila(st, "inactiva", caso, "Otros", "juguetes", false, "V135 Test");
                fila(st, "juguetes", caso, "Otros", "juguetes", true, "V135 Test");
                fila(st, "forzado", caso, "Otros", "suplementos", true, "V135 Forzado");
                fila(st, "ajena", caso, "Remera", "indumentaria", true, "V135 Test");
                fila(st, "joystick", "Joystick Sony PS5 Dualsense", "Joystick", "tecnologia", true, "V135 Test");
                st.executeUpdate("UPDATE productos SET sub_categoria = 'volante' WHERE url = '" + url("joystick") + "'");
                fila(st, "gorra", "PlayStation 5 Snapback - Gorra", "Consola", "tecnologia", true, "V135 Test");
                fila(st, "locked-sub", "Zelda - Nintendo Switch", "Videojuego", "tecnologia", true, "V135 Test");
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = '" + url("locked-sub") + "'");

                st.execute(migracion());

                assertThat(estado(st, url("bloqueada"))).containsExactly("Otros", "");
                assertThat(estado(st, url("inactiva"))[0]).isEqualTo(ACCESORIO);
                assertThat(rubro(st, "juguetes")).as("a site with no forced rubro: tecnologia").isEqualTo("tecnologia");
                assertThat(rubro(st, "forzado")).as("a forced rubro is kept").isEqualTo("tecnologia");
                assertThat(estado(st, url("ajena"))[0]).as("another category is not a source").isEqualTo("Remera");
                assertThat(estado(st, url("joystick"))).as("a stale sub is replaced by the rule").containsExactly("Joystick", "gamepad");
                assertThat(estado(st, url("gorra"))[0]).isEqualTo("Gorra");
                assertThat(rubro(st, "gorra")).isEqualTo("indumentaria");
                assertThat(estado(st, url("locked-sub"))).as("a locked row keeps its sub").containsExactly("Videojuego", "");

                String m = migracion();
                String movimientos = m.substring(m.indexOf("WITH rx ("), m.indexOf("-- 3. Sub of"));
                String subs = m.substring(m.indexOf("WITH regla ("));
                assertThat(st.executeUpdate(movimientos)).as("a second apply moves nothing").isZero();
                assertThat(st.executeUpdate(subs)).as("a second apply changes no sub").isZero();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the category is technology and exists in the lookup table")
    void laCategoriaExiste() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM categoria WHERE nombre = '" + ACCESORIO + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        assertThat(CategoryGroups.canonicalCategories()).contains(ACCESORIO);
        assertThat(CategoryGroups.esCategoriaTech(ACCESORIO)).isTrue();
    }

    @Test
    @DisplayName("the documented rollback repoints the accessories to Otros, empties the new subs and drops the category")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sitio());
                fila(st, "acc", "Funda Para Nintendo Switch Oled", "Otros", "tecnologia", true, "V135 Test");
                fila(st, "juego", "Zelda - Nintendo Switch", "Videojuego", "tecnologia", true, "V135 Test");
                fila(st, "consola", "Consola Retro Game Stick 4K", "Consola", "tecnologia", true, "V135 Test");
                fila(st, "mando", "Joystick Sony PS5 Dualsense", "Joystick", "tecnologia", true, "V135 Test");
                fila(st, "volante", "Volante Logitech G29 Racing PS5", "Joystick", "tecnologia", true, "V135 Test");
                st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                        + "VALUES ('arredo', 999135, 'Accesorios Switch', '" + ACCESORIO + "')");
                st.execute(migracion());
                assertThat(estado(st, url("acc"))[0]).isEqualTo(ACCESORIO);
                assertThat(estado(st, url("juego"))[1]).isEqualTo("switch");

                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.execute(DocumentedRollback.sqlFor("V135"));

                assertThat(estado(st, url("acc"))).containsExactly("Otros", "");
                assertThat(estado(st, url("juego"))[1]).isEmpty();
                assertThat(estado(st, url("consola"))[1]).isEmpty();
                assertThat(estado(st, url("mando"))[1]).as("the older rule: control -> gamepad").isEqualTo("gamepad");
                assertThat(estado(st, url("volante"))[1]).as("the older rule: volante").isEqualTo("volante");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM categoria WHERE nombre = '" + ACCESORIO + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sitio_categoria WHERE origen_id = 999135 AND categoria IS NULL")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static String url(String n) {
        return "https://v135.test/" + n;
    }

    private static String sitio() {
        return "INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('V135 Test', 'v135test',"
                + " 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING";
    }

    private static void fila(Statement st, String n, String nombre, String categoria, String rubro, boolean activo, String sitio) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('" + url(n) + "', '" + sitio + "', '"
                + nombre.replace("'", "''") + "', 1, '" + categoria + "', '" + rubro + "', " + activo + ")");
    }

    private static void sembrar(Connection c, List<Fila> filas) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo)"
                + " VALUES (?, 'V135 Test', ?, 1, ?, 'tecnologia', true)")) {
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

    private static String rubro(Statement st, String n) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT rubro FROM productos WHERE url = '" + url(n) + "'")) {
            assertThat(rs.next()).isTrue();
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
                String[] partes = linea.split("\t", 2);
                filas.add(new Fila(url("agree-" + filas.size()), partes[0], partes[1]));
            }
        }
        for (String[] e : EXTRA) filas.add(new Fila(url("extra-" + filas.size()), e[0], e[1]));
        return filas;
    }

    private String migracion() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
