package ar.scraper.indices;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExtrapoladorTest {

    @Test
    void ipcComponeLaUltimaVariacionMensualObservada() {
        Serie serie = new Serie(Indice.IPC, List.of(
                new PuntoIndice(Indice.IPC, LocalDate.parse("2026-01-31"), 100.0),
                new PuntoIndice(Indice.IPC, LocalDate.parse("2026-02-28"), 110.0))); // +10% ese mes

        double proyectado = Extrapolador.proyectar(serie, LocalDate.parse("2026-03-30"));

        assertThat(proyectado).isCloseTo(121.0, within(0.5)); // ~110 * 1.10
    }

    @Test
    void usdOficialSeProyectaPlanoPorqueEsUnaCotizacionFijadaPorBcra() {
        Serie serie = new Serie(Indice.USD_OFICIAL, List.of(
                new PuntoIndice(Indice.USD_OFICIAL, LocalDate.parse("2026-09-17"), 1535.0)));

        double proyectado = Extrapolador.proyectar(serie, LocalDate.parse("2026-09-25"));

        assertThat(proyectado).isEqualTo(1535.0);
    }
}
