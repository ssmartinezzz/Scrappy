package ar.scraper.indices;

import ar.scraper.testsupport.TestRubros;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeflactorPorRubroTest {

    private final DeflactorPorRubro deflactor = TestRubros.deflactor();

    @Test
    void tecnologiaDeflactaConUsdOficial() {
        assertThat(deflactor.resolver("tecnologia", "GPU")).isEqualTo(Indice.USD_OFICIAL);
    }

    @Test
    void cualquierOtroRubroDeflactaConIpc() {
        assertThat(deflactor.resolver("indumentaria", "Remera")).isEqualTo(Indice.IPC);
        assertThat(deflactor.resolver("suplementos", "Creatina")).isEqualTo(Indice.IPC);
        assertThat(deflactor.resolver("oficina", "Silla")).isEqualTo(Indice.IPC);
    }

    @Test
    void unRubroNuloODesconocidoDeflactaConIpc() {
        assertThat(deflactor.resolver(null, null)).isEqualTo(Indice.IPC);
        assertThat(deflactor.resolver("rubro-inexistente", "GPU")).isEqualTo(Indice.IPC);
    }

    @Test
    void laCategoriaPisaAlRubro() {
        assertThat(deflactor.resolver("hogar", "TV y Audio")).isEqualTo(Indice.USD_OFICIAL);
        assertThat(deflactor.resolver("tecnologia", null)).isEqualTo(Indice.USD_OFICIAL);
    }

    @Test
    void laCategoriaPuedeBajarUnRubroEnDolaresAIpc() {
        DeflactorPorRubro conIpcEnTecnologia = new DeflactorPorRubro(
                Map.of("Mouse", "IPC")::get, Map.of("tecnologia", "USD_OFICIAL")::get);

        assertThat(conIpcEnTecnologia.resolver("tecnologia", "Mouse")).isEqualTo(Indice.IPC);
        assertThat(conIpcEnTecnologia.resolver("tecnologia", "GPU")).isEqualTo(Indice.USD_OFICIAL);
    }
}
