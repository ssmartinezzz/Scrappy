package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("The ML input is streamed to its file")
@DisplayName("PythonRunner.ejecutar(ProductosParaMl) — the input goes to the file inside the scoring slot")
@DisabledOnOs(OS.WINDOWS)
class PythonRunnerProductosStreamTest {

    private static final String DOCUMENTO = "[{\"url\":\"http://a\",\"nombre\":\"café\"}]";

    private final List<String> eventos = new ArrayList<>();
    private final HistorialParaMl historial = new HistorialParaMl() {
        @Override public void preparar(Path hist, String productosJson) {
            eventos.add("string " + productosJson);
            escribir(hist);
        }
        @Override public void preparar(Path hist, Path productos) {
            try {
                eventos.add("archivo " + Files.readString(productos));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            escribir(hist);
        }
        @Override public void descartar(Path hist) {
            eventos.add("descartar");
            try { Files.deleteIfExists(hist); } catch (IOException e) { throw new RuntimeException(e); }
        }
        private void escribir(Path hist) {
            try { Files.writeString(hist, "{}"); } catch (IOException e) { throw new RuntimeException(e); }
        }
    };
    private PythonRunner runner;
    private String pythonExeAntes;
    private Path productos;

    @TempDir
    Path dir;

    /** The scoring files live in the temp dir, so no other test class sharing the cwd can touch them. */
    private PythonRunner runnerCon(HistorialParaMl h) {
        return new PythonRunner(ar.scraper.scrape.StatusEvents.NONE, h) {
            @Override Path directorioDeTrabajo() { return dir; }
        };
    }

    @BeforeEach
    void setUp() {
        pythonExeAntes = System.getProperty("PYTHON_EXE");
        productos = dir.resolve("ml_productos.json");
        runner = runnerCon(historial);
    }

    @AfterEach
    void tearDown() {
        if (pythonExeAntes == null) System.clearProperty("PYTHON_EXE");
        else System.setProperty("PYTHON_EXE", pythonExeAntes);
    }

    /** A Python that records the input it was given and fails, so the output file is never touched. */
    private void pythonQueFalla() throws Exception {
        Path script = dir.resolve("fake_python.sh");
        Files.writeString(script, "#!/bin/sh\n[ \"$1\" = \"-c\" ] && { echo no; exit 0; }\n"
                + "cat \"$2\" > \"" + dir.resolve("visto.json") + "\"\nexit 3\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.setProperty("PYTHON_EXE", script.toString());
    }

    @Test
    @DisplayName("Python reads the streamed document from ml_productos.json, and the history reads the same file")
    void pythonAndHistoryReadTheStreamedFile() throws Exception {
        pythonQueFalla();

        var salida = runner.ejecutarEscribiendo(out -> out.write(DOCUMENTO.getBytes(StandardCharsets.UTF_8)));

        assertThat(salida).isNull();
        assertThat(Files.readString(dir.resolve("visto.json"))).isEqualTo(DOCUMENTO);
        assertThat(eventos).containsExactly("archivo " + DOCUMENTO, "descartar");
    }

    @Test
    @DisplayName("the file is written while the scoring slot is held")
    void writtenInsideTheReservation() throws Exception {
        pythonQueFalla();
        var enCurso = new AtomicBoolean();

        runner.ejecutarEscribiendo(out -> {
            enCurso.set(runner.isScoringEnCurso());
            out.write(DOCUMENTO.getBytes(StandardCharsets.UTF_8));
        });

        assertThat(enCurso).isTrue();
        assertThat(runner.isScoringEnCurso()).isFalse();
    }

    @Test
    @DisplayName("with a run in flight the request is dropped before it writes anything")
    void rejectedWhenARunIsInFlight() throws Exception {
        pythonQueFalla();
        Files.writeString(productos, "[\"de la otra corrida\"]");
        var escribio = new AtomicBoolean();

        Object salida = runner.conReservaDeScoring(() -> runner.ejecutarEscribiendo(out -> {
            escribio.set(true);
            out.write(DOCUMENTO.getBytes(StandardCharsets.UTF_8));
        }));

        assertThat(salida).isNull();
        assertThat(escribio).isFalse();
        assertThat(Files.readString(productos)).isEqualTo("[\"de la otra corrida\"]");
        assertThat(eventos).isEmpty();
    }

    @Test
    @DisplayName("a product that cannot be serialized leaves the pipeline an empty list, as the string form did")
    void serializationFailureLeavesAnEmptyList() throws Exception {
        pythonQueFalla();

        var salida = runner.ejecutarEscribiendo(out -> {
            out.write("[{\"url\":".getBytes(StandardCharsets.UTF_8));
            throw new IllegalStateException("producto roto");
        });

        assertThat(salida).isNull();
        assertThat(Files.readString(dir.resolve("visto.json"))).isEqualTo("[]");
        assertThat(eventos).containsExactly("archivo []", "descartar");
    }

    @Test
    @DisplayName("a write that fails on disk is NOT_RUN: Python never starts and the history is discarded")
    void ioFailureIsNotRun() throws Exception {
        pythonQueFalla();

        var salida = runner.ejecutarEscribiendo(out -> { throw new IOException("disco lleno"); });

        assertThat(salida).isNull();
        assertThat(dir.resolve("visto.json")).doesNotExist();
        assertThat(eventos).containsExactly("descartar");
        assertThat(runner.isScoringEnCurso()).isFalse();
    }

    @Test
    @DisplayName("a history that only implements the string form still gets the file's content")
    void stringOnlyHistoryGetsTheFileContent() throws Exception {
        pythonQueFalla();
        var vistos = new ArrayList<String>();
        var soloTexto = new HistorialParaMl() {
            @Override public void preparar(Path hist, String productosJson) { vistos.add(productosJson); }
            @Override public void descartar(Path hist) { }
        };

        runnerCon(soloTexto)
                .ejecutarEscribiendo(out -> out.write(DOCUMENTO.getBytes(StandardCharsets.UTF_8)));

        assertThat(vistos).containsExactly(DOCUMENTO);
    }
}
