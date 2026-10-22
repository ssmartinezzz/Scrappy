package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.TestDatabaseServices;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Site registry")
@Story("V52 marks single-brand stores and gives their products the store as brand")
@DisplayName("V52 — sitio.monomarca: seed, marca rows and backfill")
class V52SitioMonomarcaTest extends PostgresTestBase {

    private static final List<String> MONOMARCA = List.of(
            "freres", "fuark", "mmartinez", "monkyforce", "harvey", "bulks", "bullbenny", "batuk",
            "tussy", "foreverbstrd", "forever", "fursten", "barnes", "vcp", "eldon", "midway",
            "inpro", "flowin", "vayzenhome", "mrplacadeco", "ambiancedeco");

    private static final List<String> MULTIMARCA = List.of(
            "zentra", "sporting", "entreno", "morashop", "vaypol", "city", "dcshoes", "maximus", "fullh4rd");

    @Test
    @DisplayName("exactly the own-label stores are monomarca, and every one has its marca row")
    void seedsMonomarcaAndRegistersTheirNames() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(column(st, "SELECT sitio_key FROM sitio WHERE monomarca"))
                    .containsExactlyInAnyOrderElementsOf(MONOMARCA);
            assertThat(column(st, "SELECT sitio_key FROM sitio WHERE NOT monomarca"))
                    .containsAll(MULTIMARCA);
            assertThat(column(st, "SELECT nombre FROM marca"))
                    .containsAll(column(st, "SELECT nombre FROM sitio WHERE monomarca"));
        }
    }

    @Test
    @DisplayName("a product carrying its store's name as brand is upserted, not silently dropped by the FK")
    void upsertOfAMonomarcaProductReportsNuevos() throws Exception {
        DatabaseService db = TestDatabaseServices.create(dataSource());

        var stats = db.upsertProductos(List.of(producto("http://monky.test/buzo", "Monkyforce")));

        assertThat(stats.nuevos()).as("0 is the signature of an FK rejection swallowed by the upsert").isEqualTo(1);
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(column(st, "SELECT marca FROM productos WHERE url = 'http://monky.test/buzo'"))
                    .containsExactly("Monkyforce");
        }
    }

    @Test
    @DisplayName("the backfill overrides NULL and stray brands of monomarca sites, and spares locked rows and other sites")
    void backfillRewritesMarcaOfMonomarcaProducts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertar(st, "http://t/monky", "Monkyforce", "NULL", "NULL");
                insertar(st, "http://t/fuark", "Fuark", "'Champion'", "NULL");
                insertar(st, "http://t/freres-lock", "Freres", "NULL", "'agent'");
                insertar(st, "http://t/sporting", "Sporting", "NULL", "NULL");

                st.execute(migracion());

                assertThat(marca(st, "http://t/monky")).isEqualTo("Monkyforce");
                assertThat(marca(st, "http://t/fuark")).isEqualTo("Fuark");
                assertThat(marca(st, "http://t/freres-lock")).as("bloqueado_por is a manual lock").isNull();
                assertThat(marca(st, "http://t/sporting")).as("multibrand site abstains").isNull();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback drops the column")
    void rollbackDropsTheColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V52"));
                assertThat(column(st, "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'sitio'")).doesNotContain("monomarca");
            } finally {
                c.rollback();
            }
        }
    }

    private static void insertar(Statement st, String url, String sitio, String marca, String bloqueadoPor)
            throws Exception {
        String at = "NULL".equals(bloqueadoPor) ? "NULL" : "'2026-01-01'";
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria, marca, "
                + "bloqueado_por, bloqueado_at) VALUES ('" + url + "', '" + sitio + "', 'Buzo', 100, "
                + "'indumentaria', 'Buzo', NULL, " + bloqueadoPor + ", " + at + ")");
        if (!"NULL".equals(marca)) {
            st.executeUpdate("INSERT INTO marca (nombre) VALUES (" + marca + ") ON CONFLICT DO NOTHING");
            st.executeUpdate("UPDATE productos SET marca = " + marca + " WHERE url = '" + url + "'");
        }
    }

    private static String marca(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT marca FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static List<String> column(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    /** V52 is idempotent, so the real file can be replayed over rows inserted after it ran. */
    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V52SitioMonomarcaTest.class
                .getResourceAsStream("/db/migration/V52__sitio_monomarca.sql"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Product producto(String url, String sitio) {
        return Product.builder()
                .sitio(sitio).nombre("Buzo Oversize").precio(10000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Buzo").genero("unisex")
                .talles(List.of("M")).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .marca(sitio).rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
