package ar.scraper.web;

import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PcPick;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import ar.scraper.web.support.SujetoDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("PCs")
@Story("Saved PCs")
@DisplayName("ApiController — Saved PCs endpoints")
class ApiControllerSavedPcsTest {

    private ScraperService service;
    private IndiceService indiceService;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private ar.scraper.pcs.SavedPcsPort pcsGuardadas;
    private GroupingService grouping;
    private PythonRunner pythonRunner;
    private OutfitService outfitService;
    private RecommendationService recommendationService;
    private ApiController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        indiceService      = mock(IndiceService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        pcsGuardadas          = mock(ar.scraper.pcs.SavedPcsPort.class);
        when(db.pcsGuardadas()).thenReturn(pcsGuardadas);
        grouping              = mock(GroupingService.class);
        pythonRunner          = mock(PythonRunner.class);
        outfitService         = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        SujetoDePrueba.entrar("ADMIN");
        controller = new ApiController(service, indiceService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    private Map<String, Object> pickBody(String slot, String url) {
        return Map.of("slot", slot, "sitio", "TestSitio", "nombre", "Parte", "precio", 100000.0,
                "url", url, "img", "https://img/x.jpg", "marca", "MarcaTest",
                "specs", Map.of("socket", "AM5", "ddr", "DDR5", "formFactor", "ATX",
                        "watts", 650, "capacidadGb", 32, "tipoMemoria", "DIMM"));
    }

    // ── POST /api/pcs/save ─────────────────────────────────────────────────

    @Test
    void savePcValidPayloadPersistsAndReturnsIdAndOk() {
        when(pcsGuardadas.guardarPc(any(), eq("Mi PC"), anyList(), eq(500000.0), eq(true), eq(650000.0), any()))
                .thenReturn(1);

        ResponseEntity<?> resp = controller.savePc(Map.of(
                "nombre", "Mi PC",
                "picks", List.of(pickBody("mother", "https://t/mb")),
                "presupuesto", 500000.0,
                "conGpu", true,
                "totalEstimado", 650000.0));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("id").asInt()).isEqualTo(1);
        assertThat(body.path("nombre").asText()).isEqualTo("Mi PC");
    }

    @Test
    void savePcConvertsPicksWithSpecsIntoPcPicks() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), any()))
                .thenReturn(1);

        controller.savePc(Map.of(
                "nombre", "Mi PC",
                "picks", List.of(pickBody("mother", "https://t/mb")),
                "presupuesto", 500000.0,
                "conGpu", false,
                "totalEstimado", 250000.0));

        verify(pcsGuardadas).guardarPc(any(), eq("Mi PC"), argThat(list -> {
            @SuppressWarnings("unchecked")
            List<PcPick> picks = (List<PcPick>) list;
            PcPick p = picks.get(0);
            return picks.size() == 1
                    && "mother".equals(p.slot())
                    && "https://t/mb".equals(p.url())
                    && "AM5".equals(p.specs().socket())
                    && p.specs().watts() == 650;
        }), eq(500000.0), eq(false), eq(250000.0), any());
    }

    @Test
    void savePcSkipsPickWithBlankUrl() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), any()))
                .thenReturn(1);
        Map<String, Object> sinUrl = Map.of("slot", "cpu", "url", "");

        controller.savePc(Map.of(
                "nombre", "Mi PC",
                "picks", List.of(pickBody("mother", "https://t/mb"), sinUrl),
                "presupuesto", 0.0,
                "conGpu", false,
                "totalEstimado", 0.0));

        verify(pcsGuardadas).guardarPc(any(), any(), argThat(list -> ((List<?>) list).size() == 1),
                anyDouble(), anyBoolean(), anyDouble(), any());
    }

    @Test
    void savePcMissingSpecsAbstains() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), any()))
                .thenReturn(1);
        Map<String, Object> sinSpecs = Map.of("slot", "cpu", "url", "https://t/cpu");

        controller.savePc(Map.of(
                "nombre", "Mi PC",
                "picks", List.of(sinSpecs),
                "presupuesto", 0.0,
                "conGpu", false,
                "totalEstimado", 0.0));

        verify(pcsGuardadas).guardarPc(any(), any(), argThat(list -> {
            @SuppressWarnings("unchecked")
            List<PcPick> picks = (List<PcPick>) list;
            return picks.get(0).specs().socket().isEmpty() && picks.get(0).specs().watts() == 0;
        }), anyDouble(), anyBoolean(), anyDouble(), any());
    }

    @Test
    void savePcDbFailureReturns500WithOkFalse() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), any()))
                .thenReturn(-1);

        ResponseEntity<?> resp = controller.savePc(Map.of(
                "nombre", "x", "picks", List.of(), "presupuesto", 0.0, "conGpu", false, "totalEstimado", 1000.0));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    @Test
    void savePcWithGamaPersistsTheMappedGama() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), eq(Gama.ALTA)))
                .thenReturn(1);

        controller.savePc(Map.of(
                "nombre", "Mi PC",
                "picks", List.of(pickBody("mother", "https://t/mb")),
                "presupuesto", 500000.0,
                "conGpu", true,
                "totalEstimado", 650000.0,
                "gama", "alta"));

        verify(pcsGuardadas).guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), eq(Gama.ALTA));
    }

    @Test
    void savePcWithoutGamaPassesNull() {
        when(pcsGuardadas.guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), isNull()))
                .thenReturn(1);

        controller.savePc(Map.of(
                "nombre", "Mi PC", "picks", List.of(), "presupuesto", 0.0, "conGpu", false, "totalEstimado", 0.0));

        verify(pcsGuardadas).guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), isNull());
    }

    @Test
    void savePcWithInvalidGamaReturns400AndNeverPersists() {
        ResponseEntity<?> resp = controller.savePc(Map.of(
                "nombre", "Mi PC", "picks", List.of(), "presupuesto", 0.0, "conGpu", false,
                "totalEstimado", 0.0, "gama", "ultra"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(pcsGuardadas, never()).guardarPc(any(), any(), anyList(), anyDouble(), anyBoolean(), anyDouble(), any());
    }

    // ── GET /api/pcs/saved ──────────────────────────────────────────────────

    @Test
    void getSavedPcsReturnsListFromDb() {
        Map<String, Object> pc1 = Map.of("id", 1, "nombre", "PC 1", "totalEstimado", 100.0);
        Map<String, Object> pc2 = Map.of("id", 2, "nombre", "PC 2", "totalEstimado", 200.0);
        when(pcsGuardadas.obtenerPcsGuardadas(any())).thenReturn(List.of(pc1, pc2));

        ResponseEntity<?> resp = controller.getSavedPcs();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        List<?> body = (List<?>) resp.getBody();
        assertThat(body).hasSize(2);
    }

    // ── DELETE /api/pcs/saved/{id} ─────────────────────────────────────────

    @Test
    void deleteSavedPcFoundReturnsOkTrue() {
        when(pcsGuardadas.eliminarPcGuardada(any(), eq(3))).thenReturn(true);

        ResponseEntity<?> resp = controller.deleteSavedPc(3);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
    }

    @Test
    void deleteSavedPcNotFoundReturns404WithOkFalse() {
        when(pcsGuardadas.eliminarPcGuardada(any(), eq(999))).thenReturn(false);

        Allure.parameter("id", 999);
        ResponseEntity<?> resp = controller.deleteSavedPc(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    // ── PATCH /api/pcs/saved/{id}/nombre ───────────────────────────────────

    @Test
    void renameSavedPcValidPayloadUpdatesAndReturnsOk() {
        when(pcsGuardadas.renombrarPc(any(), eq(5), eq("Mi PC"))).thenReturn(true);

        ResponseEntity<?> resp = controller.renameSavedPc(5, Map.of("nombre", "Mi PC"));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(pcsGuardadas).renombrarPc(any(), eq(5), eq("Mi PC"));
    }

    @Test
    void renameSavedPcBlankNombreReturns400() {
        ResponseEntity<?> resp = controller.renameSavedPc(5, Map.of("nombre", "   "));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(pcsGuardadas, never()).renombrarPc(any(), anyInt(), anyString());
    }

    @Test
    void renameSavedPcNotFoundReturns404() {
        when(pcsGuardadas.renombrarPc(any(), eq(99), eq("x"))).thenReturn(false);

        Allure.parameter("id", 99);
        ResponseEntity<?> resp = controller.renameSavedPc(99, Map.of("nombre", "x"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }
}
