package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run 40 (2026-10-02, POSIX install): {@code ml_train.py} died on {@code import numpy} because the
 * interpreter found was the system python3, which nobody had given the ML libraries.
 */
@Epic("ML")
@Feature("Training")
@Story("Training is skipped, visibly, when the interpreter lacks its libraries")
@DisplayName("PythonRunner — chequeo previo al entrenamiento")
class PythonRunnerTrainingPreflightTest {

    @Test
    @DisplayName("missing libraries skip training and say so in the training status")
    void sinLibreriasNoEntrenaYLoDice() {
        PythonRunner sinLibrerias = new PythonRunner() {
            @Override String detectarPython() { return "python-fake"; }
            @Override boolean tieneDependenciasDeEntrenamiento(String python) { return false; }
        };

        sinLibrerias.entrenarEnBackground(true, false, 0);

        PythonRunner.TrainingStatus estado = sinLibrerias.getTrainingStatus();
        assertThat(estado.phase()).isEqualTo("skipped");
        assertThat(estado.running()).isFalse();
        assertThat(estado.msg()).contains("numpy", "scikit-learn", "psycopg2");
    }

    @Test
    @DisplayName("an interpreter that cannot run has no training libraries")
    void unInterpreteInexistenteNoTieneLibrerias() {
        assertThat(new PythonRunner().tieneDependenciasDeEntrenamiento("/nonexistent/python")).isFalse();
    }

    @Test
    @DisplayName("the POSIX installer's ML venv is a local candidate, ahead of the PATH")
    void elVenvDeMlEsCandidato() {
        assertThat(PythonRunner.candidatosLocales("/app/scraper"))
                .contains("/app/scraper/../_tools/ml-venv/bin/python");
    }
}
