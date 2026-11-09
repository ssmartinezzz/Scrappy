package ar.scraper.web;

import ar.scraper.web.support.Wire;


import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that {@code GET /api/sitios} includes a non-null
 * {@code rubro} field for every object in the {@code base[]} array.
 *
 * <p>Mirrors the {@link CatalogoPrecioRangeTest} convention: {@code SitiosController}
 * is instantiated as a plain POJO with Mockito-mocked collaborators.
 * No {@code @WebMvcTest} or full Spring context is required.</p>
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Sitios rubro")
@DisplayName("SitiosController — Sitios rubro field")
class SitiosRubroTest {

    private ScraperService service;
    private ScraperConfig config;
    private SitiosController controller;

    @BeforeEach
    void setUp() {
        wireController();

        when(config.getSitiosActivos()).thenReturn(List.of(
                new ScraperConfig.SiteConfig("maximus", "https://www.maximus.com.ar", "tecnologia"),
                new ScraperConfig.SiteConfig("barnes",  "https://barnesindustries.com.ar/productos/", "indumentaria"),
                new ScraperConfig.SiteConfig("easy",    "https://www.easy.com.ar", "indumentaria")
        ));
        when(config.getPrecioMinimo()).thenReturn(0.0);
        when(config.getPrecioMaximo()).thenReturn(300000.0);
        when(config.getMoneda()).thenReturn("ARS");
        when(service.getSitiosExtras()).thenReturn(List.of());
    }

    @Step("Wire SitiosController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        config                = mock(ScraperConfig.class);

        controller = new SitiosController(service, config, SiteRegistry.forTesting(Map.of(
                "easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "config", false, "construccion"))));
    }

    @Test
    void sitiosBaseEntriesAllIncludeRubroField() {
        ResponseEntity<?> resp = controller.getSitios();
        JsonNode body = Wire.data(resp);
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
        JsonNode base = Wire.data(resp).path("base");

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
        JsonNode base = Wire.data(resp).path("base");

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

    @Test
    void mixedSiteShowsItsDefaultRubroInsteadOfTheConfigFallback() {
        ResponseEntity<?> resp = controller.getSitios();
        JsonNode base = Wire.data(resp).path("base");

        JsonNode easy = null;
        for (JsonNode site : base) {
            if ("easy".equals(site.path("nombre").asText())) {
                easy = site;
                break;
            }
        }
        assertThat(easy).isNotNull();
        assertThat(easy.path("rubro").asText()).isEqualTo("construccion");
    }
}
