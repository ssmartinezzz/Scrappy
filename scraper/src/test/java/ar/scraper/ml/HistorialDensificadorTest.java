package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Price history input")
@Story("Densify the change log into one step point per day")
@DisplayName("HistorialDensificador — change log to daily step points")
class HistorialDensificadorTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    private static HistorialEntry e(String fecha, double precio) {
        return new HistorialEntry(fecha, precio);
    }

    @Test
    @DisplayName("a single old change is carried forward as a flat line up to today")
    void unSoloCambioSeExtiendeHastaHoy() {
        var out = HistorialDensificador.densificar(List.of(e("2026-10-30", 100.0)), HOY, 60);

        assertThat(out).extracting(HistorialEntry::fecha)
                .containsExactly("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02");
        assertThat(out).extracting(HistorialEntry::precio).containsOnly(100.0);
    }

    @Test
    @DisplayName("a price step holds the previous price until the day it changes")
    void elEscalonSostieneElPrecioAnterior() {
        var out = HistorialDensificador.densificar(
                List.of(e("2026-10-31", 100.0), e("2026-11-02", 80.0)), HOY, 60);

        assertThat(out).containsExactly(
                e("2026-10-31", 100.0), e("2026-11-01", 100.0), e("2026-11-02", 80.0));
    }

    @Test
    @DisplayName("the window is the last N days: older changes seed the first day")
    void laVentanaCortaYElCambioPrevioDefineElPrecioInicial() {
        var out = HistorialDensificador.densificar(
                List.of(e("2025-01-01", 500.0), e("2026-10-30", 300.0)), HOY, 5);

        assertThat(out).hasSize(5);
        assertThat(out.getFirst()).isEqualTo(e("2026-10-29", 500.0));
        assertThat(out.get(1)).isEqualTo(e("2026-10-30", 300.0));
        assertThat(out.getLast()).isEqualTo(e("2026-11-02", 300.0));
    }

    @Test
    @DisplayName("never more than the window, and no points before the first change")
    void nuncaMasDeLaVentana() {
        var out = HistorialDensificador.densificar(List.of(e("2020-01-01", 1.0)), HOY, 60);

        assertThat(out).hasSize(60);
        assertThat(out.getLast().fecha()).isEqualTo("2026-11-02");
    }

    @Test
    @DisplayName("empty history and future-only changes produce nothing")
    void sinHistorialNoHayPuntos() {
        assertThat(HistorialDensificador.densificar(List.of(), HOY, 60)).isEmpty();
        assertThat(HistorialDensificador.densificar(List.of(e("2026-12-01", 5.0)), HOY, 60)).isEmpty();
    }
}
