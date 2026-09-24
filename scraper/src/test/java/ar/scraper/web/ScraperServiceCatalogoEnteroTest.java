package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run leaves in the in-memory catalog when it covered only some sites.
 *
 * <p>Nothing in the database moved: measured 2026-09-24, run 22 (5 tech sites)
 * closed with 951 products over 15.907 active rows, {@code 0 desactivados}.
 * {@code /api/data} looked fine because it queries SQL; everything that reads
 * the snapshot went dark until the next full scrape.</p>
 */
@Epic("Scraping")
@Feature("Aggregation")
@Story("A partial run does not shrink the in-memory catalog")
@DisplayName("ScraperService — el catálogo en memoria tras una corrida parcial")
class ScraperServiceCatalogoEnteroTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("a partial run keeps the WHOLE catalog in memory, not just its own sites")
    void unaCorridaParcialNoAchicaElCatalogo() {
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        var productos = Mockito.mock(ar.scraper.catalog.ProductPort.class);

        Product tech  = producto("Fullh4rd", "u-tech");
        Product ropa1 = producto("Freres", "u-ropa-1");
        Product ropa2 = producto("Freres", "u-ropa-2");

        AggregatedResult delBatch = new AggregatedResult(
                List.of(tech), Map.of("Fullh4rd", 1), Map.of("Fullh4rd", "timeout"),
                ResultAggregator.calcularFacets(List.of(tech)), 0, 0,
                Map.of("Fullh4rd", new ResultAggregator.ExtractionStats("Fullh4rd", 1, 1, 0)));

        List<Product> activos = List.of(tech, ropa1, ropa2);
        Mockito.when(productos.cargarProductos()).thenReturn(activos);
        Mockito.when(aggregator.fromDBParcial(Mockito.eq(activos), Mockito.any(), Mockito.anySet()))
                .thenReturn(new AggregatedResult(activos,
                        Map.of("Fullh4rd", 1, "Freres", 2), Map.of(),
                        ResultAggregator.calcularFacets(activos), 0, 0));

        ScraperService parcial = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator,
                db.scrapeRun(), db.sitios(), db.mlOutput(), db.siteRegistry(), productos,
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class));

        AggregatedResult resultado = parcial.catalogoEntero(delBatch);

        assertThat(resultado.productos())
                .as("the sites this run never visited stay in the catalog")
                .containsExactlyInAnyOrder(tech, ropa1, ropa2);
        assertThat(resultado.erroresPorSitio())
                .as("errors are facts of THIS run and cannot be derived from the database")
                .isEqualTo(delBatch.erroresPorSitio());
        assertThat(resultado.statsPorSitio()).isEqualTo(delBatch.statsPorSitio());
    }

    @Test
    @DisplayName("an empty database leaves the batch alone instead of serving nothing")
    void baseVaciaDegradaAlBatch() {
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        var productos = Mockito.mock(ar.scraper.catalog.ProductPort.class);
        Mockito.when(productos.cargarProductos()).thenReturn(List.of());

        Product p = producto("Fullh4rd", "u-tech");
        AggregatedResult delBatch = new AggregatedResult(List.of(p), Map.of(), Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 0, 0);

        ScraperService parcial = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator,
                db.scrapeRun(), db.sitios(), db.mlOutput(), db.siteRegistry(), productos,
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class));

        assertThat(parcial.catalogoEntero(delBatch)).isSameAs(delBatch);
    }

    private static Product producto(String sitio, String url) {
        return new Product(sitio, "n-" + url, 1000, null, url, "", "Otros", "", List.of(),
                Product.MlScore.EMPTY, "", "indumentaria", false, false, null, null, 1, "",
                Product.VisualAttrs.EMPTY);
    }

}
