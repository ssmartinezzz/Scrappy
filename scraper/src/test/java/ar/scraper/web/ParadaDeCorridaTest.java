package ar.scraper.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@DisplayName("ParadaDeCorrida")
class ParadaDeCorridaTest {

    @Test
    @DisplayName("raises the flag, stops the pool and closes the browsers; rearmar lowers the flag")
    void detiene() {
        ParadaDeCorrida parada = new ParadaDeCorrida();
        ExecutorService pool = mock(ExecutorService.class);
        AtomicBoolean cerrado = new AtomicBoolean();

        parada.detener(new OutOfMemoryError(), pool, () -> cerrado.set(true));

        assertThat(parada.detenida()).isTrue();
        verify(pool).shutdownNow();
        assertThat(cerrado).isTrue();
        parada.rearmar();
        assertThat(parada.detenida()).isFalse();
    }

    @Test
    @DisplayName("a cleanup that fails is attached to the cause and does not skip the next step")
    void limpiezaNoEnmascara() {
        ParadaDeCorrida parada = new ParadaDeCorrida();
        ExecutorService pool = mock(ExecutorService.class);
        RuntimeException falloPool = new RuntimeException("pool");
        RuntimeException falloNavegador = new RuntimeException("browser");
        doThrow(falloPool).when(pool).shutdownNow();
        Throwable causa = new OutOfMemoryError("heap");

        parada.detener(causa, pool, () -> { throw falloNavegador; });

        assertThat(causa).hasMessage("heap");
        assertThat(causa.getSuppressed()).containsExactly(falloPool, falloNavegador);
    }

    @Test
    @DisplayName("without a pool only the browsers are closed")
    void sinPool() {
        AtomicBoolean cerrado = new AtomicBoolean();

        new ParadaDeCorrida().detener(new RuntimeException(), null, () -> cerrado.set(true));

        assertThat(cerrado).isTrue();
    }
}
