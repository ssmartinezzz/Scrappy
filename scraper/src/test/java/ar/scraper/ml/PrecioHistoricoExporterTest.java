package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialImportPort;
import ar.scraper.catalog.HistorialPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("ML Pipeline")
@Feature("Price history input")
@Story("Java exports the ML history from precio_historico")
@DisplayName("PrecioHistoricoExporter — derived, ephemeral ML history file")
class PrecioHistoricoExporterTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HistorialPort historial = mock(HistorialPort.class);
    private final HistorialImportPort importador = mock(HistorialImportPort.class);
    private final PrecioHistoricoExporter exporter =
            new PrecioHistoricoExporter(historial, importador, RELOJ);

    @TempDir
    Path dir;

    @Test
    @DisplayName("writes the densified table history of the products being scored, keyed by url")
    void escribeElHistorialDensificadoPorUrl() throws Exception {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of(
                "http://a", List.of(new HistorialEntry("2026-10-31", 100.0),
                                    new HistorialEntry("2026-11-02", 80.0))));
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, """
                [{"url":"http://a","precio":80},{"url":"http://b","precio":5},{"url":"","precio":1}]""");

        verify(historial).getHistorialPrecios(List.of("http://a", "http://b"));
        JsonNode json = MAPPER.readTree(hist.toFile());
        assertThat(json.path("http://a")).hasSize(3);
        assertThat(json.path("http://a").get(1).path("fecha").asText()).isEqualTo("2026-11-01");
        assertThat(json.path("http://a").get(1).path("precio").asDouble()).isEqualTo(100.0);
        assertThat(json.path("http://a").get(2).path("precio").asDouble()).isEqualTo(80.0);
        assertThat(json.has("http://b")).isFalse();
    }

    @Test
    @DisplayName("an empty table still leaves a valid, empty history file")
    void tablaVaciaDejaUnJsonVacio() throws Exception {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, "[{\"url\":\"http://a\",\"precio\":1}]");

        assertThat(MAPPER.readTree(hist.toFile()).size()).isZero();
    }

    @Test
    @DisplayName("descartar removes the file and tolerates its absence")
    void descartarBorraElArchivo() throws Exception {
        Path hist = Files.writeString(dir.resolve("ml_historial.json"), "{}");

        exporter.descartar(hist);
        exporter.descartar(hist);

        assertThat(hist).doesNotExist();
    }

    private static final String LEGACY = """
            {"http://a":[{"fecha":"2026-10-01","precio":100.0},{"fecha":"2026-10-02","precio":100.0},
                         {"fecha":"2026-10-03","precio":90.0}]}""";

    @Test
    @DisplayName("a pre-existing history file is the old ML-owned one: imported as changes, then retired")
    void importaElArchivoLegacyAntesDeExportar() throws Exception {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        Files.writeString(dir.resolve("precio_historico.json"), LEGACY);
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, "[{\"url\":\"http://a\",\"precio\":90}]");

        verify(importador).importarCambios(Map.of("http://a", List.of(
                new HistorialEntry("2026-10-01", 100.0), new HistorialEntry("2026-10-03", 90.0))));
        assertThat(dir.resolve("precio_historico.json.imported")).hasContent(LEGACY);
        assertThat(MAPPER.readTree(hist.toFile()).size()).isZero(); // lo que escribió Java, no el legacy
    }

    @Test
    @DisplayName("a derived file left by a killed run is overwritten, never imported as legacy")
    void unDerivadoHuerfanoNoSeImporta() throws Exception {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        Path hist = Files.writeString(dir.resolve("ml_historial.json"), LEGACY);

        exporter.preparar(hist, "[]");

        verify(importador, never()).importarCambios(anyMap());
        assertThat(MAPPER.readTree(hist.toFile()).size()).isZero();
    }

    @Test
    @DisplayName("without a legacy file nothing is imported")
    void sinLegacyNoImporta() {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        exporter.preparar(dir.resolve("ml_historial.json"), "[]");

        verify(importador, never()).importarCambios(anyMap());
    }

    @Test
    @DisplayName("a failed import keeps the legacy file aside, still exports, and retries next run")
    void unaImportacionFallidaSeReintenta() throws Exception {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        doThrow(new IllegalStateException("db caída")).when(importador).importarCambios(anyMap());
        Files.writeString(dir.resolve("precio_historico.json"), LEGACY);
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, "[]");

        assertThat(dir.resolve("precio_historico.json.legacy")).hasContent(LEGACY);
        assertThat(MAPPER.readTree(hist.toFile()).size()).isZero();

        org.mockito.Mockito.reset(importador);
        exporter.preparar(hist, "[]");

        verify(importador, times(1)).importarCambios(anyMap());
        assertThat(dir.resolve("precio_historico.json.legacy")).doesNotExist();
        assertThat(dir.resolve("precio_historico.json.imported")).hasContent(LEGACY);
    }
}
