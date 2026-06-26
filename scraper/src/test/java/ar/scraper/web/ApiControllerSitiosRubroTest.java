package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that {@code GET /api/sitios} includes a non-null
 * {@code rubro} field for every object in the {@code base[]} array.
 *
 * <p>Mirrors the {@link ApiControllerPrecioRangeTest} convention: {@code ApiController}
 * is instantiated as a plain POJO with Mockito-mocked collaborators.
 * No {@code @WebMvcTest} or full Spring context is required.</p>
 */
class ApiControllerSitiosRubroTest {

    private ScraperService service;
    private InflacionService inflacionService;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private GroupingService grouping;
    private PythonRunner pythonRunner;
    private OutfitService outfitService;
    private RecommendationService recommendationService;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        service               = mock(ScraperService.class);
        inflacionService      = mock(InflacionService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        grouping              = mock(GroupingService.class);
        pythonRunner          = mock(PythonRunner.class);
        outfitService         = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);

        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);

        when(config.getSitiosActivos()).thenReturn(List.of(
                new ScraperConfig.SiteConfig("maximus", "https://www.maximus.com.ar", "tecnologia"),
                new ScraperConfig.SiteConfig("barnes",  "https://barnesindustries.com.ar/productos/", "indumentaria")
        ));
        when(config.getPrecioMinimo()).thenReturn(0.0);
        when(config.getPrecioMaximo()).thenReturn(300000.0);
        when(config.getMoneda()).thenReturn("ARS");
        when(service.getSitiosExtras()).thenReturn(List.of());
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
    }

    @Test
    void sitiosBaseEntriesAllIncludeRubroField() {
        ResponseEntity<?> resp = controller.getSitios();
        JsonNode body = (JsonNode) resp.getBody();
        JsonNode base = body.path("base");

        assertThat(base.isArray()).isTrue();
        assertThat(base).isNotEmpty();
        for (JsonNode site : base) {
            assertThat(site.has("rubro"))
                    .as("site %s must have rubro field", site.path("nombre").asText())
                    .isTrue();
            assertThat(site.path("rubro").asText())
                    .as("rubro must be non-empty for %s", site.path("nombre").asText())
                    .isNotBlank();
        }
    }

    @Test
    void maximusSiteHasTecnologiaRubro() {
        ResponseEntity<?> resp = controller.getSitios();
        JsonNode base = ((JsonNode) resp.getBody()).path("base");

        JsonNode maximus = null;
        for (JsonNode site : base) {
            if ("maximus".equals(site.path("nombre").asText())) {
                maximus = site;
                break;
            }
        }
        assertThat(maximus).isNotNull();
        assertThat(maximus.path("rubro").asText()).isEqualTo("tecnologia");
    }

    @Test
    void barnesSiteHasIndumentariaRubro() {
        ResponseEntity<?> resp = controller.getSitios();
        JsonNode base = ((JsonNode) resp.getBody()).path("base");

        JsonNode barnes = null;
        for (JsonNode site : base) {
            if ("barnes".equals(site.path("nombre").asText())) {
                barnes = site;
                break;
            }
        }
        assertThat(barnes).isNotNull();
        assertThat(barnes.path("rubro").asText()).isEqualTo("indumentaria");
    }
}
