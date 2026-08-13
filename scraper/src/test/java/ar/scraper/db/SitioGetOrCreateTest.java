package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, design E7.
 *
 * <p>`V23` puts a real FK on {@code productos.sitio}. On its own that FK is a
 * trap in this codebase: a scrape of a site not yet in {@code sitio} would
 * violate it inside {@code ProductRepository}'s swallowed-error path, which
 * logs and returns {@code UpsertStats(0,0,0,0)} — surfacing as {@code "0
 * nuevos"} and never as an error. So the FK ships together with a
 * get-or-create inside {@code sp_upsert_run}: the site row is created before
 * the product that references it, making the constraint unfalsifiable for
 * anything the scraper writes.</p>
 *
 * <p>Every assertion here checks {@code nuevos()} <b>before</b> any column
 * value, for exactly that reason: {@code 0} is the signature of a swallowed
 * rollback, and a column-only assertion would let a fully failed upsert pass
 * as "no rows, no mismatch".</p>
 */
@Epic("Persistence")
@Feature("Site")
@Story("A site row is created before the product that references it")
@DisplayName("sitio — get-or-create dentro del upsert (E7)")
class SitioGetOrCreateTest extends PostgresTestBase {

    @Test
    @DisplayName("Distintas grafías del MISMO sitio no rebotan contra la FK")
    void grafiasDistintasDelMismoSitio() throws Exception {
        DatabaseService db = new DatabaseService(dataSource());

        // 'Vcp' es como lo sembro V18. El scraper escribe 'VCP' y 'vcp'.
        // Los tres son el mismo sitio: sitioKey() los manda a 'vcp'. Una FK
        // sobre `nombre` los trataria como tres sitios distintos y rebotaria
        // dos — es exactamente el bug que hizo mover la FK a `sitio_key`.
        var stats = db.upsertProductos(List.of(
                producto("http://grafias.test/a", "Remera A", "VCP"),
                producto("http://grafias.test/b", "Remera B", "vcp"),
                producto("http://grafias.test/c", "Remera C", "Vcp")));

        assertThat(stats.nuevos())
                .as("si la FK mirara el nombre de display, esto seria 0 y el error nunca se veria")
                .isEqualTo(3);

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(DISTINCT sitio) AS grafias, count(DISTINCT sitio_key) AS claves "
                             + "FROM productos WHERE url LIKE 'http://grafias.test/%'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("grafias"))
                    .as("las tres grafias se conservan: productos.sitio es lo que reporto el scraper")
                    .isEqualTo(3);
            assertThat(rs.getInt("claves"))
                    .as("pero colapsan a UNA identidad")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("La FK productos.sitio_key -> sitio(sitio_key) existe")
    void fkExiste() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT constraint_name FROM information_schema.table_constraints "
                             + "WHERE table_schema='public' AND constraint_name='fk_productos_sitio'")) {
            assertThat(rs.next())
                    .as("E7: sin esta FK, productos.sitio sigue siendo un string suelto")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Un sitio desconocido se crea solo, y el upsert NO se traga un error")
    void sitioDesconocidoSeCreaSolo() throws Exception {
        DatabaseService db = new DatabaseService(dataSource());
        String sitioNuevo = "Tienda Que No Existia";

        var stats = db.upsertProductos(List.of(producto(
                "http://nueva.test/remera", "Remera Lisa", sitioNuevo)));

        // PRIMERO el conteo: 0 es la firma exacta de un rollback tragado.
        assertThat(stats.nuevos())
                .as("si la FK falló, esto es 0 y el error nunca se vio")
                .isEqualTo(1);

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sitio_key, plataforma, es_premium, rubro_forzado, origen "
                             + "FROM sitio WHERE nombre = '" + sitioNuevo + "'")) {
            assertThat(rs.next()).as("la fila de sitio se creó").isTrue();
            assertThat(rs.getString("sitio_key"))
                    .as("misma normalización que SiteClassification.sitioKey()")
                    .isEqualTo("tiendaquenoexistia");
            assertThat(rs.getString("plataforma"))
                    .as("el default de ScraperFactory.crear")
                    .isEqualTo("tiendanube");
            assertThat(rs.getBoolean("es_premium")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("origen"))
                    .as("'historico' es el estado que V18 inventó para justo esto")
                    .isEqualTo("historico");
        }
    }

    @Test
    @DisplayName("Un sitio ya sembrado NO se pisa: conserva plataforma y es_premium")
    void sitioExistenteNoSePisa() throws Exception {
        DatabaseService db = new DatabaseService(dataSource());

        var stats = db.upsertProductos(List.of(producto(
                "http://harvey.test/soquete", "Soquete Ozzy Black", "Harvey")));

        assertThat(stats.nuevos()).isEqualTo(1);

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT es_premium, origen FROM sitio WHERE nombre = 'Harvey'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("es_premium"))
                    .as("Harvey es el único SITIOS_PREMIUM — un get-or-create que pisara lo perdería")
                    .isTrue();
            assertThat(rs.getString("origen"))
                    .as("sigue siendo un sitio de config, no pasa a historico")
                    .isEqualTo("config");
        }
    }

    private Product producto(String url, String nombre, String sitio) {
        Product.MlScore ml = new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard");
        return new Product(sitio, nombre, 12000.0, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), ml, "", "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }
}
