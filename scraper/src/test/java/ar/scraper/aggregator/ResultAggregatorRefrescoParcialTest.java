package ar.scraper.aggregator;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ResultAggregator#fromDBParcial} — the progressive in-run catalog
 * refresh.
 *
 * <p><b>Why this exists.</b> {@code ScraperService.ejecutarScraping} refreshes
 * the in-memory catalog every time a site finishes, so the dashboard fills up
 * while the run is still going. It did that by calling {@link
 * ResultAggregator#fromDB}, which re-enriches the WHOLE catalog — including a
 * price-history query covering every product in it. With 23 active sites that
 * is 23 full-catalog history loads per run, when only one site's products can
 * possibly have changed.</p>
 *
 * <p>{@code fromDBParcial} re-enriches only the URLs the finished site just
 * wrote and carries the previous snapshot's signals over for everything else.
 * The database stays the source of truth for every product <em>field</em> —
 * the products still come from {@code cargarProductos()} — so the only thing
 * being reused is the two derived signals, and only for products whose inputs
 * demonstrably did not change.</p>
 *
 * <p>The load-bearing test is {@link
 * #soloLosProductosRefrescadosLleganAlEnricher()}: it pins the cost model
 * itself. Delete it and the optimization can silently revert to full
 * re-enrichment while every other assertion here still passes.</p>
 */
@Epic("Aggregation & Grouping")
@Feature("Refresco parcial durante el scraping")
@Story("fromDBParcial")
@DisplayName("ResultAggregator — fromDBParcial re-enriches only what changed")
class ResultAggregatorRefrescoParcialTest {

    private NormalizerService    normalizer;
    private PythonRunner         pythonRunner;
    private MlEnricher           mlEnricher;
    private SenalEnricher        senalEnricher;
    private FinanciacionEnricher financiacionEnricher;
    private DatabaseService      db;
    private ResultAggregator     aggregator;

    @BeforeEach
    void setUp() {
        normalizer           = mock(NormalizerService.class);
        pythonRunner         = mock(PythonRunner.class);
        mlEnricher           = mock(MlEnricher.class);
        senalEnricher        = mock(SenalEnricher.class);
        financiacionEnricher = mock(FinanciacionEnricher.class);
        db                   = mock(DatabaseService.class);

        // Default doubles: deterministic pure functions of the product, exactly
        // like the real enrichers (same product + same history -> same signal).
        // That is what makes carry-over and re-enrichment indistinguishable in
        // the equivalence test — and it SHOULD be indistinguishable.
        when(senalEnricher.enriquecer(anyList())).thenAnswer(inv -> sellarSenal(inv.getArgument(0), 1));
        when(financiacionEnricher.enriquecer(anyList())).thenAnswer(inv -> sellarFinan(inv.getArgument(0), 1));

        aggregator = new ResultAggregator(
                normalizer, pythonRunner, mlEnricher, senalEnricher, financiacionEnricher, db);
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────

    @Step("Build product {url} from site {sitio} at price {precio}")
    private static Product producto(String sitio, String url, double precio, String categoria) {
        return new Product(sitio, "Producto " + url, precio, null, url, "",
                categoria, "unisex", List.of("M"), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "");
    }

    /** Stamps a buy signal derived from the product, tagged with {@code pasada}. */
    private static List<Product> sellarSenal(List<Product> productos, int pasada) {
        List<Product> out = new ArrayList<>(productos.size());
        for (Product p : productos) {
            out.add(conSenales(p, new Product.SenalCompra("senal-p" + pasada + "-" + p.url(),
                    (int) p.precio()), p.finan()));
        }
        return out;
    }

    /** Stamps a financing signal derived from the product, tagged with {@code pasada}. */
    private static List<Product> sellarFinan(List<Product> productos, int pasada) {
        List<Product> out = new ArrayList<>(productos.size());
        for (Product p : productos) {
            out.add(conSenales(p, p.senal(), new Product.SenalFinanciacion(
                    "finan-p" + pasada + "-" + p.url(), p.precio(), 0, 0, 0, 0)));
        }
        return out;
    }

    private static Product conSenales(Product p, Product.SenalCompra senal, Product.SenalFinanciacion finan) {
        return new Product(p.sitio(), p.nombre(), p.precio(), p.precioOriginal(), p.url(),
                p.imagenUrl(), p.categoria(), p.genero(), p.talles(), p.ml(), p.marca(),
                p.rubro(), p.gymrat(), p.marcaPremium(), senal, finan,
                p.cantidadUnidades(), p.subCategoria(), p.visual());
    }

    /** The catalog as {@code cargarProductos()} returns it: active rows, price ascending. */
    private static List<Product> catalogoDb(Product... productos) {
        List<Product> lista = new ArrayList<>(List.of(productos));
        lista.sort(Comparator.comparingDouble(Product::precio));
        return lista;
    }

    private static Product buscar(AggregatedResult r, String url) {
        return r.productos().stream().filter(p -> url.equals(p.url())).findFirst().orElseThrow();
    }

    // ─── Equivalence against fromDB, the oracle ──────────────────────────────

    @Test
    @DisplayName("produces the same snapshot fromDB would, when the reused signals are still valid")
    void equivaleAFromDbCuandoLasSenalesReutilizadasSiguenVigentes() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("freres", "u/2", 2000, "Buzo"),
                producto("midway", "u/3", 3000, "Remera"),
                producto("midway", "u/4", 4000, "Camperas"));

        AggregatedResult previo   = aggregator.fromDB(catalogo);
        AggregatedResult completo = aggregator.fromDB(catalogo);
        AggregatedResult parcial  = aggregator.fromDBParcial(catalogo, previo, Set.of("u/3", "u/4"));

        assertThat(parcial.productos()).isEqualTo(completo.productos());
        assertThat(parcial.conteoPorSitio()).isEqualTo(completo.conteoPorSitio());
        assertThat(parcial.facets()).isEqualTo(completo.facets());
        assertThat(parcial.minPrecio()).isEqualTo(completo.minPrecio());
        assertThat(parcial.maxPrecio()).isEqualTo(completo.maxPrecio());
    }

    // ─── The cost model itself ───────────────────────────────────────────────

    @Test
    @DisplayName("only the refreshed URLs reach the enricher — the whole point of the method")
    @SuppressWarnings("unchecked")
    void soloLosProductosRefrescadosLleganAlEnricher() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("freres", "u/2", 2000, "Buzo"),
                producto("midway", "u/3", 3000, "Remera"),
                producto("midway", "u/4", 4000, "Camperas"));
        AggregatedResult previo = aggregator.fromDB(catalogo);

        ArgumentCaptor<List<Product>> captor = ArgumentCaptor.forClass(List.class);
        aggregator.fromDBParcial(catalogo, previo, Set.of("u/3", "u/4"));

        org.mockito.Mockito.verify(senalEnricher, org.mockito.Mockito.atLeastOnce())
                .enriquecer(captor.capture());
        List<Product> ultimaLlamada = captor.getValue();

        assertThat(ultimaLlamada).extracting(Product::url)
                .containsExactlyInAnyOrder("u/3", "u/4");
    }

    @Test
    @DisplayName("a site touching nothing new still does not re-enrich the catalog")
    @SuppressWarnings("unchecked")
    void sinUrlsRefrescadasNoSeEnriqueceNada() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("freres", "u/2", 2000, "Buzo"));
        AggregatedResult previo = aggregator.fromDB(catalogo);

        ArgumentCaptor<List<Product>> captor = ArgumentCaptor.forClass(List.class);
        aggregator.fromDBParcial(catalogo, previo, Set.of());

        org.mockito.Mockito.verify(senalEnricher, org.mockito.Mockito.atLeastOnce())
                .enriquecer(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    // ─── Carry-over semantics ────────────────────────────────────────────────

    @Test
    @DisplayName("an untouched product keeps the signals computed on the previous refresh")
    void productoIntactoConservaLasSenalesDelRefrescoAnterior() {
        // Enrichers now return a DIFFERENT value on every call, so a recomputed
        // signal is distinguishable from a carried-over one.
        AtomicInteger pasada = new AtomicInteger(0);
        when(senalEnricher.enriquecer(anyList()))
                .thenAnswer(inv -> sellarSenal(inv.getArgument(0), pasada.incrementAndGet()));
        when(financiacionEnricher.enriquecer(anyList()))
                .thenAnswer(inv -> sellarFinan(inv.getArgument(0), pasada.get()));

        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 2000, "Buzo"));
        AggregatedResult previo = aggregator.fromDB(catalogo);
        assertThat(buscar(previo, "u/1").senal().senal()).isEqualTo("senal-p1-u/1");

        AggregatedResult parcial = aggregator.fromDBParcial(catalogo, previo, Set.of("u/2"));

        assertThat(buscar(parcial, "u/1").senal().senal())
                .as("untouched product keeps pass 1's signal")
                .isEqualTo("senal-p1-u/1");
        assertThat(buscar(parcial, "u/2").senal().senal())
                .as("refreshed product gets a freshly computed signal")
                .isEqualTo("senal-p2-u/2");
    }

    @Test
    @DisplayName("an untouched product still takes its FIELDS from the database row, not from the old snapshot")
    void productoIntactoTomaSusCamposDeLaBaseNoDelSnapshotViejo() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 2000, "Buzo"));
        AggregatedResult previo = aggregator.fromDB(catalogo);

        // Same URL, reclassified and repriced in the database by something else
        // (an agent reclassification, a cron run). fromDBParcial reuses the
        // SIGNAL, never the stale product.
        List<Product> catalogoNuevo = catalogoDb(
                producto("freres", "u/1", 1500, "Camperas"),
                producto("midway", "u/2", 2000, "Buzo"));

        AggregatedResult parcial = aggregator.fromDBParcial(catalogoNuevo, previo, Set.of("u/2"));

        Product u1 = buscar(parcial, "u/1");
        assertThat(u1.precio()).isEqualTo(1500);
        assertThat(u1.categoria()).isEqualTo("Camperas");
        assertThat(u1.senal()).isEqualTo(buscar(previo, "u/1").senal());
    }

    @Test
    @DisplayName("a product absent from the previous snapshot is enriched even if the site did not list it")
    void productoNuevoParaLaMemoriaSeEnriqueceIgual() {
        List<Product> catalogoPrevio = catalogoDb(producto("freres", "u/1", 1000, "Remera"));
        AggregatedResult previo = aggregator.fromDB(catalogoPrevio);

        // u/9 was reactivated in the DB by this run's upsert but never lived in
        // memory: there is no signal to carry over, so it has to be computed.
        List<Product> catalogoNuevo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/9", 900, "Buzo"));

        AggregatedResult parcial = aggregator.fromDBParcial(catalogoNuevo, previo, Set.of());

        assertThat(buscar(parcial, "u/9").senal().senal()).isEqualTo("senal-p1-u/9");
    }

    @Test
    @DisplayName("a product soft-deleted out of the database disappears from the snapshot")
    void productoDesactivadoDesapareceDelSnapshot() {
        List<Product> catalogoPrevio = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("freres", "u/2", 2000, "Buzo"));
        AggregatedResult previo = aggregator.fromDB(catalogoPrevio);

        List<Product> catalogoNuevo = catalogoDb(producto("freres", "u/1", 1000, "Remera"));
        AggregatedResult parcial = aggregator.fromDBParcial(catalogoNuevo, previo, Set.of());

        assertThat(parcial.productos()).extracting(Product::url).containsExactly("u/1");
    }

    // ─── Derived payload is recomputed, never carried over ───────────────────

    @Test
    @DisplayName("facets, per-site counts and the price range are recomputed over the merged catalog")
    void facetsConteoYRangoSeRecalculanSobreElCatalogoFusionado() {
        AggregatedResult previo = aggregator.fromDB(
                catalogoDb(producto("freres", "u/1", 1000, "Remera")));

        List<Product> catalogoNuevo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 5000, "Buzo"),
                producto("midway", "u/3", 3000, "Buzo"));

        AggregatedResult parcial = aggregator.fromDBParcial(catalogoNuevo, previo, Set.of("u/2", "u/3"));

        assertThat(parcial.conteoPorSitio()).containsEntry("freres", 1).containsEntry("midway", 2);
        assertThat(parcial.facets().categorias()).containsEntry("Buzo", 2L).containsEntry("Remera", 1L);
        assertThat(parcial.minPrecio()).isEqualTo(1000);
        assertThat(parcial.maxPrecio()).isEqualTo(5000);
    }

    @Test
    @DisplayName("products stay in the database's price-ascending order")
    void elOrdenPorPrecioDeLaBaseSeRespeta() {
        AggregatedResult previo = aggregator.fromDB(
                catalogoDb(producto("freres", "u/1", 1000, "Remera")));

        List<Product> catalogoNuevo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 500, "Buzo"),
                producto("midway", "u/3", 3000, "Buzo"));

        AggregatedResult parcial = aggregator.fromDBParcial(catalogoNuevo, previo, Set.of("u/2", "u/3"));

        assertThat(parcial.productos()).extracting(Product::url).containsExactly("u/2", "u/1", "u/3");
    }

    // ─── Degradation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no previous snapshot it falls back to a full fromDB")
    void sinSnapshotPrevioCaeAFromDbCompleto() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 2000, "Buzo"));

        AggregatedResult parcial  = aggregator.fromDBParcial(catalogo, null, Set.of("u/2"));
        AggregatedResult completo = aggregator.fromDB(catalogo);

        assertThat(parcial.productos()).isEqualTo(completo.productos());
    }

    @Test
    @DisplayName("a null refreshed-URL set is treated as a full refresh, never as an empty one")
    void urlsRefrescadasNulasEquivalenARefrescoCompleto() {
        List<Product> catalogo = catalogoDb(
                producto("freres", "u/1", 1000, "Remera"),
                producto("midway", "u/2", 2000, "Buzo"));
        AggregatedResult previo = aggregator.fromDB(catalogo);

        AggregatedResult parcial  = aggregator.fromDBParcial(catalogo, previo, null);
        AggregatedResult completo = aggregator.fromDB(catalogo);

        assertThat(parcial.productos()).isEqualTo(completo.productos());
    }

    @Test
    @DisplayName("an empty catalog yields an empty snapshot without touching the enrichers")
    void catalogoVacioNoRompe() {
        AggregatedResult parcial = aggregator.fromDBParcial(List.of(), null, Set.of());
        assertThat(parcial.productos()).isEmpty();
        assertThat(parcial.minPrecio()).isZero();
        assertThat(parcial.maxPrecio()).isZero();
    }
}
