package ar.scraper.indices;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeflactorPorRubroTest {

    @Test
    void tecnologiaDeflactaConUsdOficial() {
        assertThat(DeflactorPorRubro.resolver("tecnologia")).isEqualTo(Indice.USD_OFICIAL);
    }

    @Test
    void cualquierOtroRubroDeflactaConIpc() {
        assertThat(DeflactorPorRubro.resolver("indumentaria")).isEqualTo(Indice.IPC);
        assertThat(DeflactorPorRubro.resolver("suplementos")).isEqualTo(Indice.IPC);
        assertThat(DeflactorPorRubro.resolver("oficina")).isEqualTo(Indice.IPC);
        assertThat(DeflactorPorRubro.resolver(null)).isEqualTo(Indice.IPC);
    }
}
