package ar.scraper.db;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.indices.IndiceService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.ApiController;
import ar.scraper.web.ScraperService;
import ar.scraper.web.support.SujetoDePrueba;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/recomendados} paginates from page <b>1</b>, and a page number
 * below that must not take the endpoint down with it.
 *
 * <p><b>The bug this pins.</b> The slice was computed as
 * {@code Math.min((page - 1) * size, total)}. That clamps the TOP of the range
 * and nothing clamps the bottom, so {@code page=0} produced {@code desde = -24}
 * and {@code subList(-24, 0)} threw {@code IndexOutOfBoundsException} — an
 * uncaught HTTP 500, stack trace and all, from a query parameter.
 *
 * <p><b>Why clamp and not 400.</b> {@code page} is documented as 1-based in
 * {@code docs/openapi.yaml}, so {@code page=0} is out of contract either way.
 * But {@code /api/data} takes the same out-of-contract {@code page=0} and
 * answers with the first page; two endpoints reading the same parameter must
 * not disagree about what an invalid value means, and of the two behaviours the
 * one that already ships is the one that does not crash.
 *
 * <p>{@code size} carries the mirror image of the same defect: {@code hasta =
 * desde + size} with a non-positive {@code size} lands below {@code desde}, and
 * {@code subList(0, -1)} throws just the same. Both are clamped.
 *
 * <p>Found by {@code tests/perf/}, which asked for {@code page=0} because it
 * copied the 0-based convention of {@code /api/data}. No unit test had ever
 * passed this endpoint anything but a valid page.
 *
 * <p>Lives in {@code ar.scraper.db} like its sibling recomendados tests: the
 * endpoint reads feedback and dismiss state from a real database.
 */
@Epic("REST API")
@Feature("Mejores Picks / Recomendados")
@Story("Recomendados pagination bounds")
@DisplayName("ApiController — Recomendados: un `page` fuera de rango no tira 500")
class ApiControllerRecomendadosPaginacionTest extends PostgresTestBase {

    private ScraperService service;
    private ApiController controller;

    private Product producto(String url) {
        return new Product("Sitio", "Producto " + url, 1000, null, url, "img",
                "Medias", "hombre", List.of(), MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY,
                1, "");
    }

    /** Thirty products: enough that page 1 of 24 is full and page 2 is partial. */
    private void catalogoDeTreinta() {
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            productos.add(producto("https://t/p" + i));
        }
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    @Step("Wire ApiController with a real DatabaseService and mocked collaborators")
    void setUp() {
        DatabaseService db = new DatabaseService(dataSource());
        service = mock(ScraperService.class);
        RecommendationService recommendationService = new RecommendationService();

        SujetoDePrueba.entrar(dataSource(), "ADMIN");

        controller = new ApiController(service, mock(IndiceService.class),
                mock(ScraperConfig.class), mock(ResultAggregator.class), db,
                mock(GroupingService.class), mock(PythonRunner.class),
                new OutfitService(recommendationService), recommendationService);
    }

    @Test
    @DisplayName("page=0 contesta la primera página en vez de tirar IndexOutOfBounds")
    void pageCeroNoRevienta() {
        catalogoDeTreinta();

        assertThatCode(() -> controller.recomendados(0, 24, null, null))
                .doesNotThrowAnyException();

        ResponseEntity<ObjectNode> resp = controller.recomendados(0, 24, null, null);
        assertThat(resp.getBody().get("items")).hasSize(24);
        // El eco es la página que se SIRVIÓ, no la que se pidió: devolver `page: 0`
        // junto a la primera página le mentiría al cliente sobre dónde está.
        assertThat(resp.getBody().get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("un page negativo se acota igual que el cero")
    void pageNegativoNoRevienta() {
        catalogoDeTreinta();

        ResponseEntity<ObjectNode> resp = controller.recomendados(-5, 24, null, null);

        assertThat(resp.getBody().get("items")).hasSize(24);
        assertThat(resp.getBody().get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("la semántica base 1 no cambia: page=1 es la primera y page=2 la segunda")
    void laPaginacionBaseUnoSigueIntacta() {
        catalogoDeTreinta();

        assertThat(controller.recomendados(1, 24, null, null).getBody().get("items")).hasSize(24);
        assertThat(controller.recomendados(2, 24, null, null).getBody().get("items")).hasSize(6);
    }

    @Test
    @DisplayName("un size <= 0 se acota por el otro extremo del rango")
    void sizeNoPositivoNoRevienta() {
        catalogoDeTreinta();

        // `hasta = desde + size` con size negativo cae por debajo de `desde`, y
        // subList(0, -1) tira igual que el índice negativo de arriba.
        assertThat(controller.recomendados(1, 0, null, null).getBody().get("items")).hasSize(1);
        assertThat(controller.recomendados(1, -1, null, null).getBody().get("items")).hasSize(1);
    }

    @Test
    @DisplayName("una página más allá del final es vacía, no un error")
    void pageMasAllaDelFinalEsVacia() {
        catalogoDeTreinta();

        ResponseEntity<ObjectNode> resp = controller.recomendados(99, 24, null, null);

        assertThat(resp.getBody().get("items")).isEmpty();
        assertThat(resp.getBody().get("total").asInt()).isEqualTo(30);
    }
}
