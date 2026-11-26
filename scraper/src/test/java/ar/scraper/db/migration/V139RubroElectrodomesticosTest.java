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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Base de datos")
@Feature("Rubro electrodomesticos")
@DisplayName("V139 — electrodomesticos: the SQL backfill agrees with RubroResolver, leaves the locked rows alone, and the rollback undoes it")
class V139RubroElectrodomesticosTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V139__rubro_electrodomesticos.sql";
    private static final String ELECTRO = "electrodomesticos";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String HOGAR = "hogar";
    private static final String TV_Y_AUDIO = "TV y Audio";
    private static final String HELADERA = "Heladera";
    private static final String PEQUENO = "Pequeño Electrodoméstico";
    private static final String OTROS = "Otros";
    private static final String FILA_SITIO = "('%s', '%s', 'vtex', false, %s, 'config')";
    private static final String URL = "https://v139.test/";
    private static final String HOJAS_DE_REPUESTOS = "(166, 258, 259, 260, 261, 262, 263, 264, 274, 275, 276, 277, 278, 279)";

    private static final List<String> CATEGORIAS = List.of(
            "Aire Acondicionado", "Aspiradora", "Calefactor", "Calefón", "Campana y Extractor", "Cocina y Horno", HELADERA,
            "Lavarropas", PEQUENO, "Termotanque", "Ventilador");
    private static final List<String> SUBS_DE_TV = List.of(
            "Televisor", "Parlante", "Soporte", "Barra de Sonido", "Proyector", "Radio", "Tocadiscos", "Equipo de Música",
            "Handy", "Consola y Mixer", "");
    private static final Map<String, String> FORZADOS = new LinkedHashMap<>();

    static {
        FORZADOS.put("v139libre", null);
        FORZADOS.put("v139tec", TECNOLOGIA);
        FORZADOS.put("v139ofi", "oficina");
        FORZADOS.put("v139hogar", HOGAR);
        FORZADOS.put("v139obra", "construccion");
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("on every kind of site the stored rubro of the 11 categories and the TV y Audio subs equals the Java resolver")
    void sqlAgreesWithResolver() throws Exception {
        Map<String, Sitio> registro = new LinkedHashMap<>();
        FORZADOS.forEach((clave, forzado) -> registro.put(clave, new Sitio(clave, clave, "vtex", false, forzado, "config")));
        RubroResolver resolver = TestRubros.resolver(SiteRegistry.forTesting(registro));
        enTransaccion(st -> {
            int i = 0;
            for (String clave : FORZADOS.keySet()) {
                for (String categoria : CATEGORIAS) {
                    insertar(st, URL + clave + "/" + (i++), clave, "Producto", categoria, "", rubroAntes(clave, HOGAR), true);
                }
                for (String sub : SUBS_DE_TV) {
                    insertar(st, URL + clave + "/" + (i++), clave, "Producto", TV_Y_AUDIO, sub, rubroAntes(clave, TECNOLOGIA), true);
                }
            }

            st.execute(migracion());

            int comparadas = 0;
            try (ResultSet rs = st.executeQuery("SELECT sitio, categoria, sub_categoria, rubro FROM productos WHERE url LIKE '" + URL + "v139%'")) {
                while (rs.next()) {
                    String clave = rs.getString("sitio");
                    assertThat(rs.getString("rubro")).as("%s / %s / '%s'", clave, rs.getString("categoria"), rs.getString("sub_categoria"))
                            .isEqualTo(resolver.resolver(clave, rs.getString("categoria"), "", rs.getString("sub_categoria")));
                    comparadas++;
                }
            }
            assertThat(comparadas).isEqualTo(FORZADOS.size() * (CATEGORIAS.size() + SUBS_DE_TV.size()));
        });
    }

    @Test
    @DisplayName("a forced tecnologia store keeps its aspiradora, and a locked row is not touched; an inactive row moves and stays inactive")
    void forcedLockedAndInactive() throws Exception {
        enTransaccion(st -> {
            insertar(st, URL + "forzada", "v139tec", "Aspiradora Robot", "Aspiradora", "", TECNOLOGIA, true);
            insertar(st, URL + "inactiva", "v139libre", "Heladera vieja", HELADERA, "", HOGAR, false);
            insertar(st, URL + "libre", "v139libre", "Heladera nueva", HELADERA, "", HOGAR, true);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, bloqueado_por, bloqueado_at) VALUES ('"
                    + URL + "bloqueada', 'v139libre', 'Heladera a mano', 1000, 'Heladera', 'hogar', 'agent', now())");

            st.execute(migracion());

            assertThat(fila(st, "forzada")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(fila(st, "inactiva")).isEqualTo(ELECTRO + "/false");
            assertThat(fila(st, "libre")).isEqualTo(ELECTRO + "/true");
            assertThat(fila(st, "bloqueada")).as("locked by hand").isEqualTo(HOGAR + "/true");
        });
    }

    @Test
    @DisplayName("Cetrogar rows with no category row read as electrodomesticos; one with a row keeps its rubro")
    void cetrogarDefault() throws Exception {
        enTransaccion(st -> {
            st.execute("UPDATE sitio SET rubro_por_defecto = 'tecnologia' WHERE sitio_key = 'cetrogar'");
            insertar(st, URL + "otros", "Cetrogar", "Casco", OTROS, "", TECNOLOGIA, true);
            insertar(st, URL + "llavero", "Cetrogar", "Llavero", "Llavero", "", TECNOLOGIA, true);
            insertar(st, URL + "notebook", "Cetrogar", "Notebook", "Notebook", "", TECNOLOGIA, true);
            insertar(st, URL + "otros-samsung", "Samsung", "Control Remoto", OTROS, "", TECNOLOGIA, true);

            st.execute(migracion());

            assertThat(text(st, "SELECT rubro_por_defecto FROM sitio WHERE sitio_key = 'cetrogar'")).isEqualTo(ELECTRO);
            assertThat(fila(st, "otros")).isEqualTo(ELECTRO + "/true");
            assertThat(fila(st, "llavero")).isEqualTo(ELECTRO + "/true");
            assertThat(fila(st, "notebook")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(fila(st, "otros-samsung")).as("Samsung's default stays tecnologia").isEqualTo(TECNOLOGIA + "/true");
            assertThat(text(st, "SELECT rubro_por_defecto FROM sitio WHERE sitio_key = 'samsung'")).isEqualTo(TECNOLOGIA);
        });
    }

    @Test
    @DisplayName("Samsung: the spare-part leaves become Pequeño Electrodoméstico with the sub the Java resolver gives; Tab covers and sensors stay Otros")
    void samsungSpareParts() throws Exception {
        SubcategoryResolver subs = new SubcategoryResolver();
        Map<String, Integer> repuestos = new LinkedHashMap<>();
        repuestos.put("Anaquel de Puerta de Heladera", 259);
        repuestos.put("Cajón Inferior de Freezer", 260);
        repuestos.put("Tapa Superior Cajón de Heladera para Vegetales", 260);
        repuestos.put("Huevera de Heladera", 261);
        repuestos.put("Estante de vidrio estándar de Heladeras BESPOKE", 166);
        repuestos.put("Fábrica De Hielo de Heladera", 264);
        repuestos.put("Depósito de Residuos de Aspiradora", 275);
        repuestos.put("Filtro + Canasta porta Filtro de Aspiradora", 276);
        repuestos.put("Boquilla de Aspiradora", 277);
        repuestos.put("Plato para Microondas", 278);
        repuestos.put("Guía para Plato de Microondas", 279);
        Map<String, Integer> tecnologia = new LinkedHashMap<>();
        tecnologia.put("Funda Smart Book Tab S9 Ultra", 137);
        tecnologia.put("Control Remoto para Smart TV", 184);
        tecnologia.put("Funda para The Freestyle", 186);
        tecnologia.put("Sensor de Movimiento Aeotec", 215);
        enTransaccion(st -> {
            sembrarHojas(st);
            int i = 0;
            for (Map.Entry<String, Integer> e : repuestos.entrySet()) {
                insertarDeSamsung(st, URL + "repuesto/" + (i++), e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : tecnologia.entrySet()) {
                insertarDeSamsung(st, URL + "tecnologia/" + (i++), e.getKey(), e.getValue());
            }

            st.execute(migracion());

            try (ResultSet rs = st.executeQuery("SELECT nombre, categoria, sub_categoria, rubro FROM productos WHERE url LIKE '" + URL + "repuesto/%'")) {
                int vistas = 0;
                while (rs.next()) {
                    String nombre = rs.getString("nombre");
                    assertThat(rs.getString("categoria")).as(nombre).isEqualTo(PEQUENO);
                    assertThat(rs.getString("sub_categoria")).as(nombre).isEqualTo(subs.resolver(nombre, PEQUENO));
                    assertThat(rs.getString("rubro")).as(nombre).isEqualTo(ELECTRO);
                    vistas++;
                }
                assertThat(vistas).isEqualTo(repuestos.size());
            }
            try (ResultSet rs = st.executeQuery("SELECT nombre, categoria, rubro FROM productos WHERE url LIKE '" + URL + "tecnologia/%'")) {
                int vistas = 0;
                while (rs.next()) {
                    assertThat(rs.getString("categoria")).as(rs.getString("nombre")).isEqualTo(OTROS);
                    assertThat(rs.getString("rubro")).as(rs.getString("nombre")).isEqualTo(TECNOLOGIA);
                    vistas++;
                }
                assertThat(vistas).isEqualTo(tecnologia.size());
            }
            assertThat(scalar(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = 'samsung' AND categoria = 'Pequeño Electrodoméstico'"))
                    .isEqualTo(14);
        });
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                insertar(st, URL + "bump/" + i, "v139libre", "Heladera " + i, HELADERA, "", HOGAR, true);
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
    @DisplayName("the documented rollback puts every row, the category rows and Cetrogar back, drops the rubro and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            insertar(st, URL + "heladera", "v139libre", "Heladera", HELADERA, "", ELECTRO, true);
            insertar(st, URL + "obra", "v139obra", "Heladera de obra", HELADERA, "", ELECTRO, true);
            insertar(st, URL + "tv", "v139libre", "Smart TV", TV_Y_AUDIO, "Televisor", ELECTRO, true);
            insertar(st, URL + "otros", "Cetrogar", "Casco", OTROS, "", ELECTRO, true);
            sembrarHojas(st);
            st.execute("UPDATE sitio_categoria SET categoria = 'Pequeño Electrodoméstico' WHERE sitio_key = 'samsung' AND origen_id IN " + HOJAS_DE_REPUESTOS);
            insertarDeSamsung(st, URL + "repuesto", "Anaquel de Puerta de Heladera", 259);
            st.execute("UPDATE productos SET categoria = 'Pequeño Electrodoméstico', sub_categoria = 'Accesorio y Repuesto', rubro = 'electrodomesticos'"
                    + " WHERE url = '" + URL + "repuesto'");

            st.execute(DocumentedRollback.sqlFor("V143"));

            st.execute(DocumentedRollback.sqlFor("V142"));
            st.execute(DocumentedRollback.sqlFor("V141"));
            st.execute(DocumentedRollback.sqlFor("V140"));
            st.execute(DocumentedRollback.sqlFor("V139"));

            assertThat(fila(st, "heladera")).isEqualTo(HOGAR + "/true");
            assertThat(fila(st, "obra")).isEqualTo(HOGAR + "/true");
            assertThat(fila(st, "tv")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(fila(st, "otros")).isEqualTo(TECNOLOGIA + "/true");
            assertThat(text(st, "SELECT categoria || '|' || sub_categoria || '|' || rubro FROM productos WHERE url = '" + URL + "repuesto'"))
                    .isEqualTo(OTROS + "||" + TECNOLOGIA);
            assertThat(text(st, "SELECT rubro_por_defecto FROM sitio WHERE sitio_key = 'cetrogar'")).isEqualTo(TECNOLOGIA);
            assertThat(scalar(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = 'samsung' AND categoria = 'Pequeño Electrodoméstico'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM rubro WHERE clave = 'electrodomesticos'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE rubro = 'electrodomesticos'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE rubro = 'hogar' AND categoria IN ('Heladera', 'Aspiradora') AND sub_categoria = ''"))
                    .isEqualTo(2);
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria = 'TV y Audio'")).isEqualTo(1);
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '139'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '138'")).isEqualTo(1);
        });
    }

    /** The rubro a row had before V139: a forced site's own, else the one its category had (hogar for an appliance, tecnologia for TV y Audio). */
    private static String rubroAntes(String clave, String rubroDeLaCategoria) {
        String forzado = FORZADOS.get(clave);
        boolean tvEnSitioForzado = TECNOLOGIA.equals(rubroDeLaCategoria) && forzado != null;
        boolean electroEnTecnologiaOOficina = HOGAR.equals(rubroDeLaCategoria) && (TECNOLOGIA.equals(forzado) || "oficina".equals(forzado));
        return tvEnSitioForzado || electroEnTecnologiaOOficina ? forzado : rubroDeLaCategoria;
    }

    private static void insertar(Statement st, String url, String sitio, String nombre, String categoria, String sub, String rubro,
                                 boolean activo) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro, activo) VALUES ('"
                + url + "', '" + sitio + "', '" + nombre.replace("'", "''") + "', 1000, '" + categoria + "', '" + sub + "', '" + rubro + "', " + activo + ")");
    }

    /** The leaves of Samsung's tree as V107 seeded them (the test base truncates {@code sitio_categoria}): spare parts mapped to Otros. */
    private static void sembrarHojas(Statement st) throws SQLException {
        st.execute("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                + "SELECT 'samsung', h, 'hoja ' || h, 'Otros' FROM unnest(ARRAY" + HOJAS_DE_REPUESTOS.replace('(', '[').replace(')', ']')
                + " || ARRAY[137, 184, 186, 215]) AS h ON CONFLICT (sitio_key, origen_id) DO UPDATE SET categoria = EXCLUDED.categoria");
    }

    private static void insertarDeSamsung(Statement st, String url, String nombre, int hoja) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, sitio_categoria_id) VALUES ('"
                + url + "', 'Samsung', '" + nombre.replace("'", "''") + "', 1000, 'Otros', 'tecnologia', "
                + "(SELECT id FROM sitio_categoria WHERE sitio_key = 'samsung' AND origen_id = " + hoja + "))");
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

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V139RubroElectrodomesticosTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
