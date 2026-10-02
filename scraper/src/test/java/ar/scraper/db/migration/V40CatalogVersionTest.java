package ar.scraper.db.migration;

import ar.scraper.db.TestDatabaseServices;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code catalog-facets-perf}, T2 — {@code catalog_version} bumps once per
 * transaction, on every table T3's cache depends on, and never on a rollback.
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("catalog_version tracks every write the facet/resumen cache depends on")
@DisplayName("V40 — catalog_version se actualiza por trigger, una vez por transacción")
class V40CatalogVersionTest extends PostgresTestBase {

    @Test
    @DisplayName("Insertar un producto nuevo (vía sp_upsert_run) sube la versión")
    void insertSubeLaVersion() throws Exception {
        long antes = leerVersion();
        upsert(producto("https://v40.test/1", "Remera", "Sitio40"));
        assertThat(leerVersion()).isGreaterThan(antes);
    }

    @Test
    @DisplayName("Actualizar un producto existente (precio distinto) sube la versión")
    void updateSubeLaVersion() throws Exception {
        upsert(producto("https://v40.test/2", "Remera", "Sitio40"));
        long antes = leerVersion();
        upsert(Product.builder()
                .sitio("Sitio40")
                .nombre("Remera")
                .precio(9999)
                .precioOriginal(null)
                .url("https://v40.test/2")
                .imagenUrl("http://img.example/x.jpg")
                .categoria("Remera")
                .genero("hombre")
                .talles(List.of("M"))
                .ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("Basicas")
                .visual(new Product.VisualAttrs("regular", "", "", ""))
                .build());
        assertThat(leerVersion()).isGreaterThan(antes);
    }

    @Test
    @DisplayName("Un DELETE crudo sobre productos sube la versión")
    void deleteSubeLaVersion() throws Exception {
        String url = "https://v40.test/3";
        upsert(producto(url, "Remera", "Sitio40"));
        long antes = leerVersion();
        ejecutar("DELETE FROM productos WHERE url = '" + url + "'");
        assertThat(leerVersion()).isGreaterThan(antes);
    }

    @Test
    @DisplayName("INSERT/UPDATE/DELETE crudos sobre producto_talle suben la versión")
    void productoTalleSubeLaVersion() throws Exception {
        String url = "https://v40.test/4";
        upsert(producto(url, "Remera", "Sitio40"));

        long antesInsert = leerVersion();
        ejecutar("INSERT INTO producto_talle (url, posicion, talle) VALUES ('" + url + "', 5, 'XXL')");
        assertThat(leerVersion()).as("insert").isGreaterThan(antesInsert);

        long antesUpdate = leerVersion();
        ejecutar("UPDATE producto_talle SET talle = 'XXXL' WHERE url = '" + url + "' AND posicion = 5");
        assertThat(leerVersion()).as("update").isGreaterThan(antesUpdate);

        long antesDelete = leerVersion();
        ejecutar("DELETE FROM producto_talle WHERE url = '" + url + "' AND posicion = 5");
        assertThat(leerVersion()).as("delete").isGreaterThan(antesDelete);
    }

    @Test
    @DisplayName("INSERT/UPDATE/DELETE crudos sobre producto_badge suben la versión")
    void productoBadgeSubeLaVersion() throws Exception {
        String url = "https://v40.test/5";
        upsert(producto(url, "Remera", "Sitio40"));

        long antesInsert = leerVersion();
        ejecutar("INSERT INTO producto_badge (url, posicion, badge) VALUES ('" + url + "', 5, 'trending')");
        assertThat(leerVersion()).as("insert").isGreaterThan(antesInsert);

        long antesUpdate = leerVersion();
        ejecutar("UPDATE producto_badge SET badge = 'nuevo' WHERE url = '" + url + "' AND posicion = 5");
        assertThat(leerVersion()).as("update").isGreaterThan(antesUpdate);

        long antesDelete = leerVersion();
        ejecutar("DELETE FROM producto_badge WHERE url = '" + url + "' AND posicion = 5");
        assertThat(leerVersion()).as("delete").isGreaterThan(antesDelete);
    }

    @Test
    @DisplayName("TRUNCATE de las tres tablas trackeadas, en UNA sentencia, sube la versión UNA sola vez")
    void truncateJuntoSubeLaVersionUnaVez() throws Exception {
        upsert(producto("https://v40.test/6", "Remera", "Sitio40"));
        long antes = leerVersion();
        ejecutar("TRUNCATE TABLE productos, producto_talle, producto_badge CASCADE");
        assertThat(leerVersion()).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("Una transacción con muchas filas (batch de sp_upsert_run) sube la versión UNA sola vez")
    void batchDeVariasFilasSubeUnaSolaVez() throws Exception {
        long antes = leerVersion();
        upsert(
                producto("https://v40.test/batch/1", "Remera", "Sitio40"),
                producto("https://v40.test/batch/2", "Zapatilla", "Sitio40"),
                producto("https://v40.test/batch/3", "Buzo", "Sitio40"),
                productoConTallesYBadges("https://v40.test/batch/4", "Campera", "Sitio40"),
                producto("https://v40.test/batch/5", "Short", "Sitio40")
        );
        assertThat(leerVersion()).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("Una transacción que hace ROLLBACK no sube la versión")
    void rollbackNoSubeLaVersion() throws Exception {
        seedSitio();
        long antes = leerVersion();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES (?, 'Sitio40', 'Producto', 1000, 'indumentaria')")) {
                ps.setString(1, "https://v40.test/rollback");
                ps.executeUpdate();
            }
            c.rollback();
        }
        assertThat(leerVersion()).isEqualTo(antes);
    }

    @Test
    @DisplayName("El upsert sigue reportando nuevos() > 0 — el trigger no lo swallowea como '0 nuevos'")
    void elUpsertSigueReportandoNuevos() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        UpsertStats stats = db.upsertProductos(
                List.of(productoConTallesYBadges("https://v40.test/nuevos", "Remera", "Sitio40")), null);
        assertThat(stats.nuevos()).isGreaterThan(0);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private long leerVersion() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM catalog_version")) {
            assertThat(rs.next()).as("catalog_version tiene su fila única").isTrue();
            return rs.getLong(1);
        }
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void seedSitio() throws Exception {
        ejecutar("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('Sitio40', 'sitio40', 'tiendanube', false, NULL, 'historico') ON CONFLICT DO NOTHING");
    }

    private void upsert(Product... productos) {
        TestDatabaseServices.create(dataSource()).upsertProductos(List.of(productos), null);
    }

    private Product producto(String url, String nombre, String sitio) {
        Product.MlScore ml = new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard");
        return Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(1000)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("http://img.example/x.jpg")
                .categoria(nombre)
                .genero("hombre")
                .talles(List.of("M"))
                .ml(ml)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("Basicas")
                .visual(new Product.VisualAttrs("regular", "", "", ""))
                .build();
    }

    private Product productoConTallesYBadges(String url, String nombre, String sitio) {
        Product.MlScore ml = new Product.MlScore(70, List.of("trending"), false, "estable", 50, 0.0, "standard");
        return Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(1000)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("http://img.example/x.jpg")
                .categoria(nombre)
                .genero("hombre")
                .talles(List.of("M", "L"))
                .ml(ml)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("Basicas")
                .visual(new Product.VisualAttrs("regular", "", "", ""))
                .build();
    }
}
