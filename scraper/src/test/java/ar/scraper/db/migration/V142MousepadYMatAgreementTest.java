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
@Feature("Categoría Mousepad y Mat")
@DisplayName("V142 — Mousepad y Mat: the SQL agrees with SubcategoryResolver and RubroResolver, moves every reference, and the rollback undoes it")
class V142MousepadYMatAgreementTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V142__categoria_mousepad_y_mat.sql";
    private static final String NOMBRES = "/v142/nombres-mousepad-escritorio.tsv";
    private static final String NUEVA = "Mousepad y Mat";
    private static final String MOUSEPAD = "Mousepad";
    private static final String MAT = "Mat Escritorio";
    private static final String ESCRITORIO = "Escritorio";
    private static final String MOTORIZADO = "Motorizado";
    private static final String OFICINA = "oficina";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String URL = "https://v142.test/";

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("over every real pad, mat and desk name the category, sub and rubro the SQL stores are the ones Java computes")
    void sqlAgreesWithJava() throws Exception {
        SubcategoryResolver subs = new SubcategoryResolver();
        RubroResolver rubros = rubroEnTiendaDeTecnologia();
        List<String[]> filas = filas();
        assertThat(filas).as("fixture rows").hasSizeGreaterThan(700);
        enTransaccion(st -> {
            Map<String, String> esperadas = new LinkedHashMap<>();
            try (PreparedStatement ps = st.getConnection().prepareStatement("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) "
                    + "VALUES (?, 'v142', ?, 1000, ?, '', ?)")) {
                int i = 0;
                for (String[] f : filas) {
                    String url = URL + "nombre/" + (i++);
                    boolean esDesk = ESCRITORIO.equals(f[0]);
                    String categoria = esDesk ? ESCRITORIO : NUEVA;
                    String sub = subs.resolver(f[1], categoria);
                    String rubro = esDesk && sub.isEmpty() ? TECNOLOGIA : rubros.resolver("v142", categoria, TECNOLOGIA, sub);
                    esperadas.put(url, categoria + "|" + sub + "|" + rubro);
                    ps.setString(1, url);
                    ps.setString(2, f[1]);
                    ps.setString(3, f[0]);
                    ps.setString(4, TECNOLOGIA);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            st.execute(migracion());

            List<String> distintas = new ArrayList<>();
            Map<String, Integer> porSub = new TreeMap<>();
            try (ResultSet rs = st.executeQuery("SELECT url, nombre, categoria, sub_categoria, rubro FROM productos WHERE url LIKE '" + URL + "nombre/%'")) {
                while (rs.next()) {
                    String sql = rs.getString("categoria") + "|" + rs.getString("sub_categoria") + "|" + rs.getString("rubro");
                    porSub.merge(rs.getString("categoria") + "/" + rs.getString("sub_categoria"), 1, Integer::sum);
                    if (!esperadas.get(rs.getString("url")).equals(sql)) {
                        distintas.add("%s%n    java=%s  sql=%s".formatted(rs.getString("nombre"), esperadas.get(rs.getString("url")), sql));
                    }
                }
            }
            assertThat(porSub.keySet()).as("every sub is exercised").contains(NUEVA + "/Antifatiga", NUEVA + "/Apoyamuñecas", NUEVA + "/Mousepad Ergonómico",
                    NUEVA + "/Mat de Escritorio", NUEVA + "/Mousepad RGB", NUEVA + "/Mousepad XL", NUEVA + "/Mousepad", ESCRITORIO + "/" + MOTORIZADO);
            assertThat(distintas).as("rows where V142 and Java disagree (of %d)", filas.size()).isEmpty();
        });
    }

    @Test
    @DisplayName("every old row, active or inactive, becomes Mousepad y Mat in oficina; a locked one keeps its sub; Escritorio takes Motorizado only with a proven motor")
    void renamesAndSpares() throws Exception {
        enTransaccion(st -> {
            insertar(st, "pad", "Mouse Pad Gamer Razer Gigantus V2 XXL", MOUSEPAD, "", true);
            insertar(st, "inactivo", "Mat de Escritorio Edge", MAT, "", false);
            insertar(st, "otra", "Notebook Lenovo", "Notebook", "", true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueado', 'v142', 'Mouse Pad Fantech MP64', 1000, 'Mousepad', 'a mano', 'tecnologia', 'agent', now())");
            insertar(st, "electrico", "Escritorio Gamer Regulable Eléctrico Evolabs Liftdesk One", ESCRITORIO, "", true);
            insertar(st, "regulable", "Escritorio Regulable En Altura Evolabs Liftdesk Core", ESCRITORIO, "", true);
            insertar(st, "manual", "Escritorio Standing Desk Manual Regulable", ESCRITORIO, "", true);
            insertar(st, "sin-motor", "Escritorio Regulable Sin Motor", ESCRITORIO, "", true);
            insertar(st, "motor", "Escritorio Doble Motor 160 cm", ESCRITORIO, "", true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "desk-bloqueado', 'v142', 'Escritorio Eléctrico Cougar', 1000, 'Escritorio', '', 'tecnologia', 'agent', now())");

            st.execute(migracion());

            assertThat(fila(st, "pad")).isEqualTo(NUEVA + "/Mousepad XL/oficina/true");
            assertThat(fila(st, "inactivo")).isEqualTo(NUEVA + "/Mat de Escritorio/oficina/false");
            assertThat(fila(st, "otra")).isEqualTo("Notebook//tecnologia/true");
            assertThat(fila(st, "bloqueado")).as("renamed, its sub kept").isEqualTo(NUEVA + "/a mano/oficina/true");
            assertThat(fila(st, "electrico")).isEqualTo(ESCRITORIO + "/Motorizado/oficina/true");
            assertThat(fila(st, "motor")).isEqualTo(ESCRITORIO + "/Motorizado/oficina/true");
            assertThat(fila(st, "regulable")).as("height-adjustable alone is not a motor").isEqualTo(ESCRITORIO + "//tecnologia/true");
            assertThat(fila(st, "manual")).isEqualTo(ESCRITORIO + "//tecnologia/true");
            assertThat(fila(st, "sin-motor")).isEqualTo(ESCRITORIO + "//tecnologia/true");
            assertThat(fila(st, "desk-bloqueado")).as("locked by hand: not touched").isEqualTo(ESCRITORIO + "//tecnologia/true");
            assertThat(scalar(st, "SELECT count(*) FROM productos WHERE categoria IN ('Mousepad', 'Mat Escritorio')")).isZero();
        });
    }

    @Test
    @DisplayName("sitio_categoria, categoria_stats, categoria_dismiss and saved_outfit_item follow the rename, and the old rows are gone")
    void everyReferenceMoves() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) VALUES ('v142', 1, 'Pads', 'Mousepad'), ('v142', 2, 'Mats', 'Mat Escritorio'), ('v142', 3, 'Desks', 'Escritorio')");
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Mousepad', now(), 7), ('Mat Escritorio', now(), 8)");
            st.executeUpdate("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('Mousepad', now()), ('Mat Escritorio', now()), ('Escritorio', now())");
            st.executeUpdate("INSERT INTO saved_outfits (nombre, total_estimado, created_at) VALUES ('v142', 0, now())");
            st.executeUpdate("INSERT INTO saved_outfit_item (outfit_id, clase, posicion, ranura, url, categoria) "
                    + "SELECT id, 'slot', 1, 'x', 'https://v142.test/item', 'Mat Escritorio' FROM saved_outfits WHERE nombre = 'v142'");

            st.execute(migracion());

            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v142' AND origen_id = 1")).isEqualTo(NUEVA);
            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v142' AND origen_id = 2")).isEqualTo(NUEVA);
            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v142' AND origen_id = 3")).isEqualTo(ESCRITORIO);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_stats WHERE categoria = 'Mousepad y Mat'")).as("one stats row, never a key violation").isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_stats WHERE categoria IN ('Mousepad', 'Mat Escritorio')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Mousepad y Mat'")).isEqualTo(2);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Escritorio'")).isEqualTo(1);
            assertThat(text(st, "SELECT categoria FROM saved_outfit_item WHERE url = 'https://v142.test/item'")).isEqualTo(NUEVA);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Mousepad', 'Mat Escritorio')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria IN ('Mousepad', 'Mat Escritorio')")).isZero();
        });
    }

    @Test
    @DisplayName("when Mousepad y Mat already has its stats row the old ones are dropped, not merged into a key violation")
    void statsOfBothNames() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Mousepad', now(), 7), ('Mousepad y Mat', now(), 9)");

            st.execute(migracion());

            assertThat(text(st, "SELECT n FROM categoria_stats WHERE categoria = 'Mousepad y Mat'")).as("the newer name wins").isEqualTo("9");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_stats WHERE categoria = 'Mousepad'")).isZero();
        });
    }

    @Test
    @DisplayName("the taxonomy rows: oficina and pisa_forzado for the category and for the motorized desk, the dollar index, and no old category")
    void taxonomyRows() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Mousepad y Mat' AND sub_categoria = ''")).isEqualTo("oficina/true");
            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Escritorio' AND sub_categoria = 'Motorizado'")).isEqualTo("oficina/true");
            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Escritorio' AND sub_categoria = ''")).isEqualTo("oficina/false");
            assertThat(text(st, "SELECT indice FROM categoria WHERE nombre = 'Mousepad y Mat'")).isEqualTo("USD_OFICIAL");
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Mousepad', 'Mat Escritorio')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE pisa_forzado")).as("the flag is for these two rows only").isEqualTo(2);
        });
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                insertar(st, "bump/" + i, "Mouse Pad Fantech MP" + i, MOUSEPAD, "", true);
            }
            insertar(st, "bump/desk", "Escritorio Electrico Cougar", ESCRITORIO, "", true);
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
            insertar(st, "uno", "Mouse Pad Gamer RGB 800x300", MOUSEPAD, "", true);
            insertar(st, "dos", "Mat Antifatiga Pro", MAT, "", true);
            insertar(st, "tres", "Escritorio Electrico Cougar", ESCRITORIO, "", true);

            st.execute(migracion());
            st.execute(migracion());

            assertThat(fila(st, "uno")).isEqualTo(NUEVA + "/Mousepad RGB/oficina/true");
            assertThat(fila(st, "dos")).isEqualTo(NUEVA + "/Antifatiga/oficina/true");
            assertThat(fila(st, "tres")).isEqualTo(ESCRITORIO + "/Motorizado/oficina/true");
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Mousepad y Mat'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Mousepad y Mat'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the documented rollback puts Mousepad and Mat Escritorio back with the rubro of the site, clears Motorizado, and drops the category and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v142t', 'v142t', 'vtex', false, 'tecnologia', 'config')");
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) VALUES ('v142', 1, 'Pads', 'Mousepad y Mat')");
            st.executeUpdate("INSERT INTO categoria_stats (categoria, updated_at, n) VALUES ('Mousepad y Mat', now(), 7)");
            st.executeUpdate("INSERT INTO categoria_dismiss (categoria, created_at) VALUES ('Mousepad y Mat', now())");
            insertar(st, "pad", "Mouse Pad Gamer Razer Gigantus V2 XXL", NUEVA, "Mousepad XL", true);
            insertar(st, "mat", "Mat Antifatiga Pro", NUEVA, "Antifatiga", true);
            insertar(st, "mat-de-escritorio", "Desk Mat Playstation", NUEVA, "Mat de Escritorio", false);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) VALUES ('"
                    + URL + "pad-tecnologia', 'v142t', 'Mouse Pad Fantech MP64', 1000, 'Mousepad y Mat', 'Mousepad', 'oficina')");
            insertar(st, "desk", "Escritorio Electrico Cougar", ESCRITORIO, MOTORIZADO, true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) VALUES ('"
                    + URL + "desk-tecnologia', 'v142t', 'Escritorio Electrico Evolabs', 1000, 'Escritorio', 'Motorizado', 'oficina')");

            st.execute(DocumentedRollback.sqlFor("V142"));

            assertThat(fila(st, "pad")).isEqualTo(MOUSEPAD + "//tecnologia/true");
            assertThat(fila(st, "mat")).isEqualTo(MAT + "//oficina/true");
            assertThat(fila(st, "mat-de-escritorio")).isEqualTo(MAT + "//oficina/false");
            assertThat(fila(st, "pad-tecnologia")).as("the forced site wins again").isEqualTo(MOUSEPAD + "//tecnologia/true");
            assertThat(fila(st, "desk")).isEqualTo(ESCRITORIO + "//oficina/true");
            assertThat(fila(st, "desk-tecnologia")).isEqualTo(ESCRITORIO + "//tecnologia/true");
            assertThat(text(st, "SELECT categoria FROM sitio_categoria WHERE sitio_key = 'v142'")).isEqualTo(MOUSEPAD);
            assertThat(text(st, "SELECT n FROM categoria_stats WHERE categoria = 'Mousepad'")).isEqualTo("7");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_dismiss WHERE categoria = 'Mousepad'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre = 'Mousepad y Mat'")).isZero();
            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Mousepad' AND sub_categoria = ''")).isEqualTo("tecnologia/false");
            assertThat(text(st, "SELECT rubro || '/' || pisa_forzado FROM categoria_rubro WHERE categoria = 'Mat Escritorio' AND sub_categoria = ''")).isEqualTo("oficina/false");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'Mousepad y Mat' OR sub_categoria = 'Motorizado'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '142'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '141'")).isEqualTo(1);
        });
    }

    private static void insertar(Statement st, String sufijo, String nombre, String categoria, String sub, boolean activo) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, activo) VALUES ('"
                + URL + sufijo + "', 'v142', '" + nombre.replace("'", "''") + "', 1000, '" + categoria + "', '" + sub + "', 'tecnologia', " + activo + ")");
    }

    private static String fila(Statement st, String sufijo) throws SQLException {
        return text(st, "SELECT categoria || '/' || sub_categoria || '/' || rubro || '/' || activo FROM productos WHERE url = '" + URL + sufijo + "'");
    }

    /** A store forced to tecnologia: the case pisa_forzado exists for. */
    private static RubroResolver rubroEnTiendaDeTecnologia() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of("v142", new SiteRegistry.Sitio(
                "v142", "v142", "vtex", false, TECNOLOGIA, "config", false, null)));
        return TestRubros.resolver(registry);
    }

    /** The database is at the latest version, where the old categories are already gone: the test puts them back inside its own transaction. */
    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v142', 'v142', 'vtex', false, NULL, 'config')");
                st.executeUpdate("INSERT INTO categoria (nombre, indice) VALUES ('Mousepad', NULL), ('Mat Escritorio', NULL) ON CONFLICT (nombre) DO NOTHING");
                st.executeUpdate("INSERT INTO categoria_rubro (categoria, sub_categoria, rubro) VALUES ('Mousepad', '', 'tecnologia'), ('Mat Escritorio', '', 'oficina') "
                        + "ON CONFLICT (categoria, sub_categoria) DO NOTHING");
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
        try (InputStream in = Objects.requireNonNull(V142MousepadYMatAgreementTest.class.getResourceAsStream(NOMBRES));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String linea; (linea = r.readLine()) != null; ) {
                if (!linea.isBlank()) filas.add(linea.split("\t", 2));
            }
        }
        return filas;
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V142MousepadYMatAgreementTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
