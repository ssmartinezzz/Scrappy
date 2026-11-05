package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.api.ApiResponse;
import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.support.SujetoDePrueba;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("REST API")
@Feature("Recomendados")
@Story("Pagination through the bounded ranking")
@DisplayName("RecomendadosController — page and size beyond the catalog")
class RecomendadosPaginationTest {

    private static final int CATALOGO = 50;

    private ScraperService service;
    private RecomendadosController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        SujetoDePrueba.entrar("ADMIN");
        controller = new RecomendadosController(service, mock(ar.scraper.feedback.FeedbackPort.class),
                new RecommendationService(), new ar.scraper.security.ActorResolver());
        List<Product> productos = IntStream.range(0, CATALOGO).mapToObj(i -> producto("https://t/" + i, 100 - i)).toList();
        when(service.getLastResult()).thenReturn(new AggregatedResult(
                productos, Map.of(), Map.of(), null, 0, 0, Map.of()));
    }

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    private static Product producto(String url, int scoreP) {
        return Product.builder()
                .sitio("TestSitio").nombre("Remera " + url).precio(10000).url(url).imagenUrl("https://img/test.jpg")
                .categoria("Remera").genero("hombre").talles(List.of())
                .ml(new Product.MlScore(scoreP, List.of(), false, "estable", scoreP, 0.0, "standard"))
                .marca("Nike").rubro("indumentaria").build();
    }

    private ResponseEntity<ApiResponse<List<ObjectNode>>> pedir(int page, int size) {
        return controller.recomendados(page, size, null, null);
    }

    @Test
    @DisplayName("a page times a size that overflows int answers 200 with an empty page and the real total")
    void pageTimesSizeOverflowGivesAnEmptyPage() {
        var respuesta = pedir(Integer.MAX_VALUE, 24);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody().getData()).isEmpty();
        assertThat(respuesta.getBody().getPage().getTotal()).isEqualTo(CATALOGO);
    }

    @Test
    @DisplayName("the largest page with the largest size answers 200 with an empty page")
    void largestPageAndSizeGiveAnEmptyPage() {
        var respuesta = pedir(Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody().getData()).isEmpty();
        assertThat(respuesta.getBody().getPage().getTotal()).isEqualTo(CATALOGO);
    }

    @Test
    @DisplayName("a huge size on the first page returns the whole catalog")
    void hugeSizeReturnsEverything() {
        var respuesta = pedir(0, Integer.MAX_VALUE);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody().getData()).hasSize(CATALOGO);
    }

    @Test
    @DisplayName("a page past the end is empty and the last real page is not")
    void pagePastTheEndIsEmpty() {
        assertThat(pedir(2, 24).getBody().getData()).hasSize(2);
        assertThat(pedir(3, 24).getBody().getData()).isEmpty();
        assertThat(pedir(1000, 24).getBody().getData()).isEmpty();
    }

    @Test
    @DisplayName("a negative page and a zero size are clamped instead of failing")
    void negativeAndZeroAreClamped() {
        var respuesta = pedir(-5, 0);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody().getData()).hasSize(1);
        assertThat(respuesta.getBody().getPage().getSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("consecutive pages do not repeat or skip a product")
    void consecutivePagesPartitionTheCatalog() {
        List<String> urls = new java.util.ArrayList<>();
        for (int pagina = 0; pagina < 5; pagina++) {
            for (ObjectNode item : pedir(pagina, 12).getBody().getData()) urls.add(item.path("nombre").asText());
        }

        assertThat(urls).hasSize(CATALOGO).doesNotHaveDuplicates();
        assertThat(urls.get(0)).isEqualTo("Remera https://t/49");
    }
}
