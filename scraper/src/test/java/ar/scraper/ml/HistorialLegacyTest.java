package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Price history input")
@Story("Collapse the ML-owned history into table-shaped changes")
@DisplayName("HistorialLegacy — one point per sighted day to price changes")
class HistorialLegacyTest {

    private static HistorialEntry e(String fecha, double precio) {
        return new HistorialEntry(fecha, precio);
    }

    @Test
    @DisplayName("unchanged sightings collapse into the first one")
    void losAvistajesSinCambioSeColapsan() {
        var out = HistorialLegacy.colapsarACambios(List.of(
                e("2026-10-01", 100.0), e("2026-10-02", 100.0), e("2026-10-03", 90.0),
                e("2026-10-04", 90.0), e("2026-10-05", 100.0)));

        assertThat(out).containsExactly(e("2026-10-01", 100.0), e("2026-10-03", 90.0),
                e("2026-10-05", 100.0));
    }

    @Test
    @DisplayName("several points on one day keep the last, like the upsert will")
    void variosPuntosDelMismoDiaQuedaElUltimo() {
        var out = HistorialLegacy.colapsarACambios(List.of(
                e("2026-10-01", 100.0), e("2026-10-02", 80.0), e("2026-10-02", 70.0)));

        assertThat(out).containsExactly(e("2026-10-01", 100.0), e("2026-10-02", 70.0));
    }

    @Test
    @DisplayName("an intra-day move that returns to the previous price is no change at all")
    void unaIdaYVueltaDelMismoDiaNoEsCambio() {
        var out = HistorialLegacy.colapsarACambios(List.of(
                e("2026-10-01", 100.0), e("2026-10-02", 80.0), e("2026-10-02", 100.0)));

        assertThat(out).containsExactly(e("2026-10-01", 100.0));
    }

    @Test
    @DisplayName("unsorted input is ordered by date and empty input stays empty")
    void ordenaPorFecha() {
        assertThat(HistorialLegacy.colapsarACambios(List.of(
                e("2026-10-03", 90.0), e("2026-10-01", 100.0))))
                .containsExactly(e("2026-10-01", 100.0), e("2026-10-03", 90.0));
        assertThat(HistorialLegacy.colapsarACambios(List.of())).isEmpty();
    }
}
