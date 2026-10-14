package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import ar.scraper.scrape.CorridaEnCurso;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run 66: a pagination cut kept 3480 of 9960 Vaypol products, and because the site ended DONE the
 * sweep deactivated the other 6536. A site that finished with an error and some products is
 * partial: its seen rows are written, but "absent" says nothing about the rest.
 */
@Epic("Persistence")
@Feature("Partial sites")
@DisplayName("ProductRepository — a partial site is written but never swept")
class ProductRepositorySoftDeletePartialSiteTest extends PostgresTestBase {

    private static final String CORTE = "paginación cortada en p59: net::ERR_NETWORK_CHANGED";

    private DatabaseService db;
    private Instant runStart;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private void sembrarSitiosConFilasViejas() throws Exception {
        db.upsertProductos(List.of(
                productoDe("Parcial", "https://parcial.com/a"),
                productoDe("Parcial", "https://parcial.com/b"),
                productoDe("Parcial", "https://parcial.com/c"),
                productoDe("Completo", "https://completo.com/x"),
                productoDe("Completo", "https://completo.com/y")));
        for (String url : List.of("https://parcial.com/a", "https://parcial.com/b", "https://parcial.com/c",
                "https://completo.com/x", "https://completo.com/y")) {
            fijarTouchedAt(url, runStart.minusSeconds(10));
        }
    }

    /** Each site re-reads one known row and finds one new; the rest of the old rows go unseen. */
    private List<Product> loQueLaCorridaVio() {
        return List.of(
                productoDe("Parcial", "https://parcial.com/a"),
                productoDe("Parcial", "https://parcial.com/nuevo"),
                productoDe("Completo", "https://completo.com/x"),
                productoDe("Completo", "https://completo.com/nuevo"));
    }

    @Test
    @DisplayName("run path: a DONE site with an error keeps its unseen rows; a clean DONE site is swept")
    void runPathSkipsTheSiteThatEndedWithAnError() throws Exception {
        sembrarSitiosConFilasViejas();
        long runId = db.crearScrapeRun(UUID.randomUUID(), runStart, null, null, List.of("Parcial", "Completo"));
        db.marcarSitioTerminado(runId, "Parcial", "DONE", 2, CORTE, Instant.now());
        db.marcarSitioTerminado(runId, "Completo", "DONE", 2, null, Instant.now());

        UpsertStats stats = db.upsertProductos(loQueLaCorridaVio(), new CorridaEnCurso(runId, runStart));

        assertThat(stats.nuevos()).isEqualTo(2);
        assertThat(estaActivo("https://parcial.com/nuevo")).isTrue();
        assertThat(estaActivo("https://parcial.com/a")).isTrue();
        assertThat(estaActivo("https://parcial.com/b")).as("unseen, but the site was cut").isTrue();
        assertThat(estaActivo("https://parcial.com/c")).isTrue();
        assertThat(estaActivo("https://completo.com/x")).isTrue();
        assertThat(estaActivo("https://completo.com/y")).as("seen the whole site, y is gone").isFalse();
        assertThat(stats.desactivados()).isEqualTo(1);
    }

    @Test
    @DisplayName("batch path: the sites the caller names as partial are left out of the sweep")
    void batchPathSkipsTheNamedPartialSites() throws Exception {
        sembrarSitiosConFilasViejas();

        UpsertStats stats = db.upsertProductos(loQueLaCorridaVio(), null, Set.of("Parcial"));

        assertThat(stats.nuevos()).isEqualTo(2);
        assertThat(estaActivo("https://parcial.com/nuevo")).isTrue();
        assertThat(estaActivo("https://parcial.com/b")).isTrue();
        assertThat(estaActivo("https://parcial.com/c")).isTrue();
        assertThat(estaActivo("https://completo.com/y")).isFalse();
        assertThat(stats.desactivados()).isEqualTo(1);
    }

    private Product productoDe(String sitio, String url) {
        return Product.builder()
                .sitio(sitio)
                .nombre("Producto " + url)
                .precio(1000.0)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("http://img.example/x.jpg")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of("M", "L"))
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .build();
    }

    private void fijarTouchedAt(String url, Instant cuando) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE productos SET touched_at = ? WHERE url = ?")) {
            ps.setObject(1, cuando.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
            ps.setString(2, url);
            assertThat(ps.executeUpdate()).as("fixture row %s must exist", url).isEqualTo(1);
        }
    }

    private boolean estaActivo(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT activo FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row %s must exist", url).isTrue();
                return rs.getBoolean(1);
            }
        }
    }
}
