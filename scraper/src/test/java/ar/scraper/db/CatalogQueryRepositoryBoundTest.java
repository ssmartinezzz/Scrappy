package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 4 (design D1/D6).
 *
 * <p>While a run is in flight the SQL surfaces serve the catalogue as it stood
 * before the run started: rows the run has already re-touched are held back, so
 * a reader sees a consistent older catalogue instead of a half-rescraped mix.
 * The predicate is {@code touched_at < started_at}, and an absent bound means
 * <b>serve everything</b> — never "bound = epoch, serve nothing".</p>
 *
 * <p><b>The bound has to enter FOUR different {@code activo} predicates</b>, not
 * one. Testing through {@code buscar} alone would leave three of them unbounded
 * while the facade looked perfect:</p>
 * <ol>
 *   <li>{@code construirWhere} — {@code p.activo}, behind {@code buscar} and the
 *       {@code contar(Where)} overload.</li>
 *   <li>{@code resumen}'s aggregate — {@code FROM productos WHERE activo}.</li>
 *   <li>{@code contar(String expresion)} — the GROUP BY overload, reached eight
 *       times from {@code facetas} and twice from {@code resumen}.</li>
 *   <li>{@code contarHija} — {@code p.activo} on a JOIN, feeding the talles and
 *       badges facets. Named by nothing: a search for "contar" does not find it,
 *       which is exactly why it gets its own test here.</li>
 * </ol>
 *
 * <p>Every instant is bound as a UTC parameter rather than written as a SQL
 * literal: a bare literal is read in the session zone, which pgjdbc takes from
 * the JVM, so the same test would mean different instants per machine.</p>
 */
@Epic("Persistence")
@Feature("Scrape run persistence (scrape-run-persistence-and-resume)")
@Story("Readers are isolated from a run in flight")
@DisplayName("CatalogQueryRepository — the reader bound reaches every activo predicate")
class CatalogQueryRepositoryBoundTest extends PostgresTestBase {

    private DatabaseService db;
    private CatalogQueryRepository repo;

    /** The run's start. "viejo" predates it; "fresco" is re-touched by the run. */
    private Instant runStart;

    private static final String VIEJO  = "https://uno.com/viejo";
    private static final String FRESCO = "https://dos.com/fresco";

    @BeforeEach
    void setUp() throws Exception {
        db = new DatabaseService(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        db.upsertProductos(List.of(
                producto("Uno", VIEJO,  "Remera Vieja",  1000.0),
                producto("Dos", FRESCO, "Remera Fresca", 2000.0)));

        // "viejo" belongs to the catalogue as it stood before the run.
        fijarTouchedAt(VIEJO, runStart.minusSeconds(10));
        // "fresco" was re-scraped by the run that is still in flight.
        fijarTouchedAt(FRESCO, runStart.plusSeconds(5));

        // Distinctive values at a position upsertProductos never writes, so these
        // assertions cannot be confused with the talles the product fixture itself
        // carries (it ships "M" and "L" for both rows).
        insertarHija("producto_talle", "talle", VIEJO,  "TALLE-VIEJO");
        insertarHija("producto_talle", "talle", FRESCO, "TALLE-FRESCO");
        insertarHija("producto_badge", "badge", VIEJO,  "BADGE-VIEJO");
        insertarHija("producto_badge", "badge", FRESCO, "BADGE-FRESCO");
    }

    // ── Surface 1: construirWhere, behind buscar ─────────────────────────────

    @Test
    @DisplayName("buscar holds back rows the run already re-touched")
    void buscarRespetaLaCota() {
        CatalogPage acotada = repo.buscar(CatalogFilter.todo(), null, 1, 50, Optional.of(runStart));

        assertThat(urls(acotada)).containsExactly(VIEJO);
        assertThat(acotada.total()).isEqualTo(1);
    }

    // ── Surface 2: resumen's own aggregate ───────────────────────────────────

    @Test
    @DisplayName("resumen's aggregate is bounded, so its total agrees with the page")
    void resumenRespetaLaCota() {
        CatalogResumen acotado = repo.resumen(Optional.of(runStart));
        CatalogPage    pagina  = repo.buscar(CatalogFilter.todo(), null, 1, 50, Optional.of(runStart));

        // 4.7: an unbounded resumen gating a bounded page is what makes the 204
        // check and the page contents disagree.
        assertThat(acotado.total()).isEqualTo(pagina.total()).isEqualTo(1);
        assertThat(acotado.maxPrecio()).isEqualTo(1000.0);
    }

    // ── Surface 3: the GROUP BY overload ─────────────────────────────────────

    @Test
    @DisplayName("the GROUP BY overload is bounded, in facetas and in resumen alike")
    void contarPorExpresionRespetaLaCota() {
        var facetas = repo.facetas(Optional.of(runStart));
        var resumen = repo.resumen(Optional.of(runStart));

        // Reached from facetas...
        assertThat(facetas.marcas()).containsOnlyKeys("Nike");
        assertThat(facetas.categorias()).containsOnlyKeys("Remera");
        // ...and from resumen, which is the call nobody remembers.
        assertThat(resumen.porSitio()).containsOnlyKeys("Uno");
    }

    // ── Surface 4: contarHija — the one no list named ────────────────────────

    @Test
    @DisplayName("contarHija is bounded, so talles and badges cannot advertise hidden products")
    void contarHijaRespetaLaCota() {
        var facetas = repo.facetas(Optional.of(runStart));

        // Without the bound here, the page shows one product while the filters
        // offer "XXL" and "trending" — values only the held-back row carries.
        assertThat(facetas.talles()).containsKey("TALLE-VIEJO").doesNotContainKey("TALLE-FRESCO");
        assertThat(facetas.badges()).containsKey("BADGE-VIEJO").doesNotContainKey("BADGE-FRESCO");
    }

    // ── Surface 5: contar(Where), pinned on its own ─────────────────────────

    @Test
    @DisplayName("the page's total is bounded independently of the page it counts")
    void contarPorWhereRespetaLaCota() {
        // A page of one hides the difference between the two overloads: the page
        // is capped by LIMIT either way, so only `total` can disprove the count.
        // With `contar(Where)` unbounded this reads 2 while the page serves the
        // single old row — the pager offering a page that does not exist.
        CatalogPage acotada = repo.buscar(CatalogFilter.todo(), null, 1, 1, Optional.of(runStart));

        assertThat(acotada.productos()).hasSize(1);
        assertThat(acotada.total())
                .as("the count and the page must come from the same predicate")
                .isEqualTo(1);
    }

    // ── D6: absent means everything, never nothing ───────────────────────────

    @Test
    @DisplayName("an absent bound serves everything — never 'bound = epoch, serve nothing'")
    void cotaAusenteSirveTodo() {
        CatalogPage    pagina  = repo.buscar(CatalogFilter.todo(), null, 1, 50, Optional.empty());
        CatalogResumen resumen = repo.resumen(Optional.empty());
        var            facetas = repo.facetas(Optional.empty());

        assertThat(urls(pagina)).containsExactlyInAnyOrder(VIEJO, FRESCO);
        assertThat(resumen.total()).isEqualTo(2);
        assertThat(resumen.porSitio()).containsOnlyKeys("Uno", "Dos");
        assertThat(facetas.talles()).containsKeys("TALLE-VIEJO", "TALLE-FRESCO");
        assertThat(facetas.badges()).containsKeys("BADGE-VIEJO", "BADGE-FRESCO");
    }

    // ── The boundary the truncation exists to protect ────────────────────────

    @Test
    @DisplayName("a row touched in the run's own first second is held back, not served")
    void filaDelMismoSegundoQuedaOculta() throws Exception {
        // touched_at == started_at fails `touched_at < started_at`, so the row is
        // held back. A javadoc in slice 1 briefly described this the other way
        // round; the arithmetic, and this assertion, are the contract.
        fijarTouchedAt(FRESCO, runStart);

        CatalogPage acotada = repo.buscar(CatalogFilter.todo(), null, 1, 50, Optional.of(runStart));

        assertThat(urls(acotada)).containsExactly(VIEJO);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<String> urls(CatalogPage page) {
        return page.productos().stream().map(Product::url).toList();
    }

    private Product producto(String sitio, String url, String nombre, double precio) {
        return new Product(
                sitio, nombre, precio, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    private void fijarTouchedAt(String url, Instant cuando) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET touched_at = ? WHERE url = ?")) {
            ps.setObject(1, cuando.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
            ps.setString(2, url);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void insertarHija(String tabla, String columna, String url, String valor) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO " + tabla + " (url, posicion, " + columna + ") VALUES (?, 5, ?) "
                             + "ON CONFLICT (url, posicion) DO UPDATE SET " + columna + " = EXCLUDED." + columna)) {
            ps.setString(1, url);
            ps.setString(2, valor);
            ps.executeUpdate();
        }
    }
}
