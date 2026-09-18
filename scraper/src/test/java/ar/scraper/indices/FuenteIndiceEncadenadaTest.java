package ar.scraper.indices;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FuenteIndiceEncadenadaTest {

    private static PuntoIndice punto(String fecha, double valor) {
        return new PuntoIndice(Indice.IPC, LocalDate.parse(fecha), valor);
    }

    private static FuenteIndicePort queFalla() {
        return indice -> { throw new FuenteIndiceException("caída de red"); };
    }

    private static FuenteIndicePort queDevuelve(List<PuntoIndice> puntos) {
        return indice -> puntos;
    }

    @Test
    void usaLaPrimeraFuenteQueRespondeConAlMenosDosPuntos() throws Exception {
        FuenteIndiceEncadenada cadena = new FuenteIndiceEncadenada(List.of(
                queDevuelve(List.of(punto("2026-01-31", 100.0), punto("2026-02-28", 110.0))),
                queFalla()));

        assertThat(cadena.descargar(Indice.IPC)).hasSize(2);
    }

    @Test
    void unaFuenteQueTiraExcepcionCaeALaSiguiente() throws Exception {
        FuenteIndiceEncadenada cadena = new FuenteIndiceEncadenada(List.of(
                queFalla(),
                queDevuelve(List.of(punto("2026-01-31", 100.0), punto("2026-02-28", 110.0)))));

        assertThat(cadena.descargar(Indice.IPC)).hasSize(2);
    }

    @Test
    void unSoloPuntoEsUnaFallaNoUnaSerie() throws Exception {
        FuenteIndiceEncadenada cadena = new FuenteIndiceEncadenada(List.of(
                queDevuelve(List.of(punto("2026-02-28", 110.0))),
                queDevuelve(List.of(punto("2026-01-31", 100.0), punto("2026-02-28", 110.0)))));

        assertThat(cadena.descargar(Indice.IPC)).hasSize(2);
    }

    @Test
    void siTodasFallanPropagaLaUltimaFalla() {
        FuenteIndiceEncadenada cadena = new FuenteIndiceEncadenada(List.of(queFalla(), queFalla()));

        assertThatThrownBy(() -> cadena.descargar(Indice.IPC)).isInstanceOf(FuenteIndiceException.class);
    }
}
