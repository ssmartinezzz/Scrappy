package ar.scraper.ml;

import ar.scraper.catalog.HistorialImportPort;
import ar.scraper.catalog.HistorialPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("ML Pipeline")
@Feature("Price history input")
@Story("The history reads its URLs from the input file")
@DisplayName("PrecioHistoricoExporter — URLs of the input file, without loading it as a tree")
class PrecioHistoricoExporterArchivoTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HistorialPort historial = mock(HistorialPort.class);
    private final PrecioHistoricoExporter exporter =
            new PrecioHistoricoExporter(historial, mock(HistorialImportPort.class), RELOJ);

    @TempDir
    Path dir;

    /** The tree walk the exporter did before it streamed. */
    private static List<String> urlsPorArbol(String json) throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        for (JsonNode p : MAPPER.readTree(json)) {
            String url = p.path("url").asText("");
            if (StringUtils.isNotBlank(url)) urls.add(url);
        }
        return new ArrayList<>(urls);
    }

    @SuppressWarnings("unchecked")
    private List<String> urlsPedidas(Runnable preparar) {
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        preparar.run();
        ArgumentCaptor<List<String>> urls = ArgumentCaptor.forClass(List.class);
        verify(historial).getHistorialPrecios(urls.capture());
        return urls.getValue();
    }

    private static final List<String> DOCUMENTOS = List.of(
            "[]",
            "[{\"url\":\"http://a\",\"precio\":1},{\"url\":\"http://b\"},{\"url\":\"http://a\"}]",
            "[{\"nombre\":\"sin url\"},{\"url\":\"\"},{\"url\":\"  \"},{\"url\":null},{\"url\":\"http://c\"}]",
            "[{\"url\":{\"x\":\"http://nested\"},\"y\":[{\"url\":\"http://no\"}]},{\"url\":\"http://d\"}]",
            "[{\"precio\":{\"url\":\"http://inner\"},\"url\":\"http://e\"},{\"url\":\"http://e\"}]",
            "[{\"url\":\"http://ñandú/caf\\u00e9\"},{\"url\":42},{\"url\":true},{\"url\":1.5}]",
            "[1,\"x\",null,[{\"url\":\"http://in-array\"}],{\"url\":\"http://f\"}]",
            "{\"no\":\"es un arreglo\"}",
            "\"texto\"",
            "");

    @Test
    @DisplayName("the file yields the same URLs, in the same order, as the old tree walk")
    void theFileYieldsTheSameUrls() throws Exception {
        for (String documento : DOCUMENTOS) {
            org.mockito.Mockito.reset(historial);
            Path productos = Files.writeString(dir.resolve("ml_productos.json"), documento);

            List<String> delArchivo = urlsPedidas(() -> exporter.preparar(dir.resolve("h.json"), productos));

            assertThat(delArchivo).as(documento).isEqualTo(urlsPorArbol(documento));
        }
    }

    @Test
    @DisplayName("the file and the string overloads ask for the same URLs")
    void fileAndStringAgree() throws Exception {
        for (String documento : DOCUMENTOS) {
            org.mockito.Mockito.reset(historial);
            Path productos = Files.writeString(dir.resolve("ml_productos.json"), documento);
            List<String> delArchivo = urlsPedidas(() -> exporter.preparar(dir.resolve("h.json"), productos));
            org.mockito.Mockito.reset(historial);

            List<String> delTexto = urlsPedidas(() -> exporter.preparar(dir.resolve("h.json"), documento));

            assertThat(delTexto).as(documento).isEqualTo(delArchivo);
        }
    }

    @Test
    @DisplayName("an unreadable file leaves the pipeline without history, as an unparseable string did")
    void unparseableFileMeansNoHistory() throws Exception {
        Path productos = Files.writeString(dir.resolve("ml_productos.json"), "[{\"url\":\"http://a\"");
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, productos);

        verify(historial, never()).getHistorialPrecios(anyList());
        assertThat(hist).doesNotExist();
    }

    @Test
    @DisplayName("a missing file leaves the pipeline without history and does not throw")
    void missingFileMeansNoHistory() {
        Path hist = dir.resolve("ml_historial.json");

        exporter.preparar(hist, dir.resolve("no-existe.json"));

        verify(historial, never()).getHistorialPrecios(anyList());
        assertThat(hist).doesNotExist();
    }

    @Test
    @DisplayName("a history written from the file is the one written from the string")
    void sameHistoryFile() throws Exception {
        String documento = DOCUMENTOS.get(1);
        var cambios = Map.of("http://a", List.of(new ar.scraper.catalog.HistorialEntry("2026-10-31", 100.0)));
        when(historial.getHistorialPrecios(anyList())).thenReturn(cambios);
        Path productos = Files.writeString(dir.resolve("ml_productos.json"), documento);

        exporter.preparar(dir.resolve("desde-archivo.json"), productos);
        exporter.preparar(dir.resolve("desde-texto.json"), documento);

        assertThat(dir.resolve("desde-archivo.json")).hasSameBinaryContentAs(dir.resolve("desde-texto.json"));
    }
}
