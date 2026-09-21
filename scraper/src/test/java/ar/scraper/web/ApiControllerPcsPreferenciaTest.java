package ar.scraper.web;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@code GET}/{@code PUT /api/pcs/preferencia} (pc-builder-gama T6): the
 * builder preference {@code /pcs} preloads from. Same wiring shape as
 * {@link ApiControllerSavedPcsTest} — the port is mocked, no Postgres needed
 * here (the real round trip through Postgres lives in
 * {@code PreferenciaArmadorRepositoryTest}).
 */
@Epic("REST API")
@Feature("PCs")
@Story("Builder preference")
@DisplayName("ApiController — PC builder preference endpoints")
class ApiControllerPcsPreferenciaTest {

    private DatabaseService db;
    private PreferenciaArmadorPort preferenciaArmador;
    private ApiController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        ScraperService service = mock(ScraperService.class);
        IndiceService indiceService = mock(IndiceService.class);
        ScraperConfig config = mock(ScraperConfig.class);
        ResultAggregator aggregator = mock(ResultAggregator.class);
        db = mock(DatabaseService.class);
        preferenciaArmador = mock(PreferenciaArmadorPort.class);
        when(db.preferenciaArmador()).thenReturn(preferenciaArmador);
        GroupingService grouping = mock(GroupingService.class);
        PythonRunner pythonRunner = mock(PythonRunner.class);
        OutfitService outfitService = mock(OutfitService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        SujetoDePrueba.entrar("ADMIN");
        controller = new ApiController(service, indiceService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    // ── GET /api/pcs/preferencia ────────────────────────────────────────────

    @Test
    void getReturns204WhenNoPreferenceSaved() {
        when(preferenciaArmador.cargar(any())).thenReturn(Optional.empty());

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void getReturns200WithSavedShape() {
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.ALTA, 1500000.0, true)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("gama").asText()).isEqualTo("alta");
        assertThat(body.get("presupuesto").asDouble()).isEqualTo(1500000.0);
        assertThat(body.get("conGpu").asBoolean()).isTrue();
    }

    @Test
    void getReturns200WithNullPresupuestoWhenNeverSet() {
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.MEDIA, null, false)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();
        ObjectNode body = resp.getBody();

        assertThat(body.get("gama").asText()).isEqualTo("media");
        assertThat(body.get("presupuesto").isNull()).isTrue();
        assertThat(body.get("conGpu").asBoolean()).isFalse();
    }

    // ── PUT /api/pcs/preferencia ────────────────────────────────────────────

    @Test
    void putValidPayloadPersistsAndReturns200() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of(
                "gama", "alta", "presupuesto", 1500000.0, "conGpu", true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ObjectNode body = resp.getBody();
        assertThat(body.get("gama").asText()).isEqualTo("alta");
        assertThat(body.get("presupuesto").asDouble()).isEqualTo(1500000.0);
        assertThat(body.get("conGpu").asBoolean()).isTrue();
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(Gama.ALTA, 1500000.0, true)));
    }

    @Test
    void putAcceptsAccentAndCaseInsensitiveGamaAndDefaultsAbsentFields() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "ECONÓMICA"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(Gama.BAJA, null, false)));
    }

    @Test
    void putMissingGamaReturns400AndNeverPersists() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("conGpu", true));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        verifyNoInteractions(preferenciaArmador);
    }

    @Test
    void putInvalidGamaReturns400AndNeverPersists() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "ultra"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        verifyNoInteractions(preferenciaArmador);
    }
}
