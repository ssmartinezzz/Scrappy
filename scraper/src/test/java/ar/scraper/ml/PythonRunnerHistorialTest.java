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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Price history input")
@Story("PythonRunner hands the ML a history derived from the table")
@DisplayName("PythonRunner — history file lifecycle around a scoring run")
@DisabledOnOs(OS.WINDOWS)
class PythonRunnerHistorialTest {

    private final List<String> eventos = new ArrayList<>();
    private final HistorialParaMl historial = new HistorialParaMl() {
        @Override public void preparar(Path hist, String productosJson) {
            eventos.add("preparar " + hist.getFileName() + " " + productosJson);
            try { Files.writeString(hist, "{}"); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        @Override public void descartar(Path hist) {
            eventos.add("descartar " + hist.getFileName());
            try { Files.deleteIfExists(hist); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    };
    private final PythonRunner runner =
            new PythonRunner(ar.scraper.scrape.StatusEvents.NONE, historial);
    private String pythonExeAntes;
    private byte[] productosAntes;
    private Path productos;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        pythonExeAntes = System.getProperty("PYTHON_EXE");
        productos = Paths.get("").toAbsolutePath().resolve("ml_productos.json");
        productosAntes = Files.exists(productos) ? Files.readAllBytes(productos) : null;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pythonExeAntes == null) System.clearProperty("PYTHON_EXE");
        else System.setProperty("PYTHON_EXE", pythonExeAntes);
        if (productosAntes != null) Files.write(productos, productosAntes);
        else Files.deleteIfExists(productos);
    }

    private void pythonFalso(String cuerpo) throws Exception {
        Path script = dir.resolve("fake_python.sh");
        Files.writeString(script, "#!/bin/sh\n[ \"$1\" = \"-c\" ] && { echo no; exit 0; }\n"
                + "echo \"$@\" > \"" + dir.resolve("argv.txt") + "\"\n" + cuerpo + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.setProperty("PYTHON_EXE", script.toString());
    }

    @Test
    @DisplayName("prepares the history before launching Python and discards it after")
    void preparaAntesYDescartaDespues() throws Exception {
        pythonFalso("test -f ml_historial.json && echo existe > \"" + dir.resolve("vio.txt")
                + "\"; exit 3");

        runner.ejecutar("[]");

        assertThat(eventos).containsExactly("preparar ml_historial.json []",
                "descartar ml_historial.json");
        assertThat(Files.readString(dir.resolve("vio.txt"))).isEqualTo("existe\n");
        assertThat(Files.readString(dir.resolve("argv.txt"))).contains("ml_historial.json");
    }

    @Test
    @DisplayName("discards the history even when Python fails")
    void descartaTambienSiFalla() throws Exception {
        pythonFalso("exit 3");

        assertThat(runner.ejecutar("[]")).isNull();

        assertThat(eventos).endsWith("descartar ml_historial.json");
    }
}
