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
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.TipoAlmacenamiento;
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

    // ── preferencias técnicas (pc-builder-deep-taxonomy T5c) ──────────────

    @Test
    void getReturns200WithTechnicalPreferencesInTheJson() {
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(
                "DDR5", "AMD", "NVIDIA", TipoAlmacenamiento.NVME, true, true);
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.ALTA, 1500000.0, true, prefs)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();
        ObjectNode body = resp.getBody();

        assertThat(body.get("ddr").asText()).isEqualTo("ddr5");
        assertThat(body.get("marcaCpu").asText()).isEqualTo("amd");
        assertThat(body.get("marcaGpu").asText()).isEqualTo("nvidia");
        assertThat(body.get("tipoAlmacenamiento").asText()).isEqualTo("nvme");
        assertThat(body.get("ramDual").asBoolean()).isTrue();
        assertThat(body.get("wifi").asBoolean()).isTrue();
    }

    @Test
    void getReturns200WithNullTechnicalFieldsAndFalseBooleansWhenNothingRequested() {
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.MEDIA, null, false)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();
        ObjectNode body = resp.getBody();

        assertThat(body.get("ddr").isNull()).isTrue();
        assertThat(body.get("marcaCpu").isNull()).isTrue();
        assertThat(body.get("marcaGpu").isNull()).isTrue();
        assertThat(body.get("tipoAlmacenamiento").isNull()).isTrue();
        assertThat(body.get("ramDual").asBoolean()).isFalse();
        assertThat(body.get("wifi").asBoolean()).isFalse();
    }

    @Test
    void putValidPayloadWithTechnicalPreferencesPersistsAndReturns200() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of(
                "gama", "alta", "ddr", "ddr4", "marcaCpu", "intel", "marcaGpu", "amd",
                "tipoAlmacenamiento", "sata", "ramDual", true, "wifi", false));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ObjectNode body = resp.getBody();
        assertThat(body.get("ddr").asText()).isEqualTo("ddr4");
        assertThat(body.get("marcaCpu").asText()).isEqualTo("intel");
        assertThat(body.get("marcaGpu").asText()).isEqualTo("amd");
        assertThat(body.get("tipoAlmacenamiento").asText()).isEqualTo("sata");
        assertThat(body.get("ramDual").asBoolean()).isTrue();
        assertThat(body.get("wifi").asBoolean()).isFalse();
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(Gama.ALTA, null, false,
                new PreferenciasDeArmado("DDR4", "INTEL", "AMD", TipoAlmacenamiento.SSD, true, false))));
    }

    @Test
    void putInvalidDdrReturns400AndNeverPersists() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "alta", "ddr", "ddr3"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("ddr");
        verifyNoInteractions(preferenciaArmador);
    }

    @Test
    void putInvalidTipoAlmacenamientoReturns400AndNeverPersists() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(
                Map.of("gama", "alta", "tipoAlmacenamiento", "ssd"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("tipoAlmacenamiento");
        verifyNoInteractions(preferenciaArmador);
    }

    // ── uso (pc-builder-homelab T6) ─────────────────────────────────────

    @Test
    void getReturns200WithUsoInTheJson() {
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.ALTA, 1500000.0, true,
                        PreferenciasDeArmado.NINGUNA, ar.scraper.pcs.Uso.HOMELAB)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();

        assertThat(resp.getBody().get("uso").asText()).isEqualTo("homelab");
    }

    @Test
    void getDefaultsUsoToGamingWhenNeverSet() {
        when(preferenciaArmador.cargar(any()))
                .thenReturn(Optional.of(new PreferenciaArmador(Gama.MEDIA, null, false)));

        ResponseEntity<ObjectNode> resp = controller.getPcsPreferencia();

        assertThat(resp.getBody().get("uso").asText()).isEqualTo("gaming");
    }

    @Test
    void putValidPayloadWithUsoPersistsAndReturns200() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "alta", "uso", "homelab"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("uso").asText()).isEqualTo("homelab");
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(
                Gama.ALTA, null, false, PreferenciasDeArmado.NINGUNA, ar.scraper.pcs.Uso.HOMELAB)));
    }

    @Test
    void putAbsentUsoDefaultsToGaming() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "alta"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("uso").asText()).isEqualTo("gaming");
    }

    @Test
    void putInvalidUsoReturns400AndNeverPersists() {
        ResponseEntity<ObjectNode> resp = controller.putPcsPreferencia(Map.of("gama", "alta", "uso", "servidor"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("uso");
        verifyNoInteractions(preferenciaArmador);
    }
}
