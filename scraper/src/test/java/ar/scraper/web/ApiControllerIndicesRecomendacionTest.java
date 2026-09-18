package ar.scraper.web;

import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;

import ar.scraper.indices.Confianza;
import ar.scraper.indices.Deflactor;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndiceService;
import ar.scraper.indices.PuntoIndice;
import ar.scraper.indices.ResumenIndice;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.testsupport.AllureSteps;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Financiación")
@Story("Indices and price recommendation")
@DisplayName("ApiController — /api/indices & price recommendation endpoints")
class ApiControllerIndicesRecomendacionTest {

    private ScraperService service;
    private IndiceService indiceService;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private HistorialPort historial;
    private ar.scraper.catalog.ProductPort productos;
    private GroupingService grouping;
    private PythonRunner pythonRunner;
    private OutfitService outfitService;
    private RecommendationService recommendationService;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        indiceService         = mock(IndiceService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        historial             = mock(HistorialPort.class);
        productos             = mock(ar.scraper.catalog.ProductPort.class);
        when(db.historial()).thenReturn(historial);
        when(db.productos()).thenReturn(productos);
        when(productos.obtenerProducto(any())).thenReturn(java.util.Optional.empty());
        grouping              = mock(GroupingService.class);
        pythonRunner          = mock(PythonRunner.class);
        outfitService         = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        controller = new ApiController(service, indiceService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    // ── GET /api/indices ─────────────────────────────────────────────────

    @Test
    void indicesReturnsIpcAndUsdResumenAndActualizado() {
        ResumenIndice ipc = new ResumenIndice(Indice.IPC, 156.3, LocalDate.parse("2025-01-01"),
                4.2, 118.0, 13.5, Confianza.OBSERVADO,
                List.of(new PuntoIndice(Indice.IPC, LocalDate.parse("2025-01-01"), 156.3)));
        ResumenIndice usd = new ResumenIndice(Indice.USD_OFICIAL, 1535.0, LocalDate.parse("2026-09-18"),
                0.3, 40.0, 5.0, Confianza.OBSERVADO,
                List.of(new PuntoIndice(Indice.USD_OFICIAL, LocalDate.parse("2026-09-18"), 1535.0)));
        when(indiceService.resumen(Indice.IPC)).thenReturn(ipc);
        when(indiceService.resumen(Indice.USD_OFICIAL)).thenReturn(usd);
        when(indiceService.ultimaActualizacion()).thenReturn("2025-01-01");

        var resp = controller.indices();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ipc").get("ultimoValor").asDouble()).isEqualTo(156.3);
        assertThat(body.get("ipc").get("variacionMensual").asDouble()).isEqualTo(4.2);
        assertThat(body.get("ipc").get("confianza").asText()).isEqualTo("observado");
        assertThat(body.get("usd").get("ultimoValor").asDouble()).isEqualTo(1535.0);
        assertThat(body.get("actualizado").asText()).isEqualTo("2025-01-01");
    }

    @Test
    void indicesSinDatosReportaConfianzaSinDatos() {
        when(indiceService.resumen(Indice.IPC)).thenReturn(ResumenIndice.sinDatos(Indice.IPC));
        when(indiceService.resumen(Indice.USD_OFICIAL)).thenReturn(ResumenIndice.sinDatos(Indice.USD_OFICIAL));

        var resp = controller.indices();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("ipc").get("confianza").asText()).isEqualTo("sin_datos");
        assertThat(body.get("ipc").get("ultimos").size()).isEqualTo(0);
    }

    // ── GET /api/recomendacion ───────────────────────────────────────────

    @Test
    void recomendacionReturnsSinDatosWhenNoHistory() {
        when(historial.getHistorialPrecios("https://a.com/1")).thenReturn(List.of());

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("senal").asText()).isEqualTo("sin_datos");
    }

    @Test
    void recomendacionReturnsComprarAhoraCuandoPrecioEnMinimo() {
        // Price at historical minimum (pctDelMin <= 10%)
        var puntos = new ArrayList<>(List.of(
                new HistorialEntry("2024-11-01", 20000.0),
                new HistorialEntry("2024-12-01", 25000.0),
                new HistorialEntry("2025-01-01", 20100.0)));  // essentially at minimum

        when(historial.getHistorialPrecios("https://a.com/1")).thenReturn(puntos);
        when(indiceService.deflactor(eq(Indice.IPC), any(), any())).thenReturn(new Deflactor(1.0, Confianza.OBSERVADO, 0));
        when(indiceService.resumen(Indice.IPC)).thenReturn(new ResumenIndice(
                Indice.IPC, 100.0, LocalDate.parse("2025-01-01"), 4.0, 50.0, 10.0, Confianza.OBSERVADO, List.of()));

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("senal").asText()).isEqualTo("comprar_ahora");
        assertThat(body.get("scoreCompra").asInt()).isEqualTo(95);
    }

    @Test
    void recomendacionDeflactaTecnologiaPorDolarYExponeIndiceYConfianza() {
        var puntos = new ArrayList<>(List.of(
                new HistorialEntry("2024-11-01", 500000.0),
                new HistorialEntry("2025-01-01", 520000.0)));
        var gpu = new ar.scraper.model.Product("Sitio", "GPU", 520000.0, null, "https://a.com/gpu", "",
                "GPU", "unisex", List.of(), ar.scraper.model.Product.MlScore.EMPTY, "", "tecnologia", false, false,
                ar.scraper.model.Product.SenalCompra.EMPTY, ar.scraper.model.Product.SenalFinanciacion.EMPTY, 1);
        when(historial.getHistorialPrecios("https://a.com/gpu")).thenReturn(puntos);
        when(productos.obtenerProducto("https://a.com/gpu")).thenReturn(java.util.Optional.of(gpu));
        when(indiceService.deflactor(eq(Indice.USD_OFICIAL), eq(LocalDate.parse("2024-11-01")), eq(LocalDate.parse("2025-01-01"))))
                .thenReturn(new Deflactor(1.10, Confianza.EXTRAPOLADO, 12));
        when(indiceService.resumen(Indice.IPC)).thenReturn(ResumenIndice.sinDatos(Indice.IPC));

        JsonNode body = AllureSteps.toJson(controller.recomendacion("https://a.com/gpu").getBody());

        assertThat(body.get("indice").asText()).isEqualTo("USD_OFICIAL");
        assertThat(body.get("confianza").asText()).isEqualTo("extrapolado");
        assertThat(body.get("diasExtrapolados").asInt()).isEqualTo(12);
        assertThat(body.get("cambioReal").asDouble()).isCloseTo(-5.5, org.assertj.core.data.Offset.offset(0.1));
        verify(indiceService, never()).deflactor(eq(Indice.IPC), any(), any());
    }

    @Test
    void recomendacionReturnsPrecioNormalForMidRangePrice() {
        var puntos = new ArrayList<>(List.of(
                new HistorialEntry("2024-11-01", 10000.0),
                new HistorialEntry("2024-12-01", 15000.0),
                new HistorialEntry("2025-01-01", 12500.0)));  // 50% of range — normal

        when(historial.getHistorialPrecios("https://a.com/1")).thenReturn(puntos);
        when(indiceService.deflactor(eq(Indice.IPC), any(), any())).thenReturn(new Deflactor(1.0, Confianza.OBSERVADO, 0));
        when(indiceService.resumen(Indice.IPC)).thenReturn(new ResumenIndice(
                Indice.IPC, 100.0, LocalDate.parse("2025-01-01"), 4.0, 50.0, 10.0, Confianza.OBSERVADO, List.of()));

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("senal").asText()).isEqualTo("precio_normal");
        assertThat(body.get("scoreCompra").asInt()).isEqualTo(50);
        assertThat(body.has("puntosHistorial")).isTrue();
    }

    @Test
    void recomendacionIncludesInflacionFieldsInResponse() {
        var puntos = new ArrayList<>(List.of(new HistorialEntry("2025-01-01", 10000.0)));
        when(historial.getHistorialPrecios("https://a.com/1")).thenReturn(puntos);
        when(indiceService.deflactor(eq(Indice.IPC), any(), any())).thenReturn(Deflactor.NEUTRO);
        when(indiceService.resumen(Indice.IPC)).thenReturn(new ResumenIndice(
                Indice.IPC, 100.0, LocalDate.parse("2025-01-01"), 4.2, 100.0, 10.0, Confianza.OBSERVADO, List.of()));

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("inflacionMensual").asDouble()).isEqualTo(4.2);
        assertThat(body.get("inflacionInteranual").asDouble()).isEqualTo(100.0);
    }
}
