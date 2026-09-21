package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EstimadorDeConsumo — watts floor + minimum certification by requested gama")
class EstimadorDeConsumoTest {

    // ── sin gama pedida: tiene que seguir dando EXACTAMENTE 450/650 ──────

    @Test
    @DisplayName("no gama requested (null) keeps the pre-existing 450W floor without a GPU")
    void sinGamaPedidaPisoEs450SinGpu() {
        assertThat(EstimadorDeConsumo.wattsMinimos(null, false)).isEqualTo(450);
    }

    @Test
    @DisplayName("no gama requested (null) keeps the pre-existing 650W floor with a GPU")
    void sinGamaPedidaPisoEs650ConGpu() {
        assertThat(EstimadorDeConsumo.wattsMinimos(null, true)).isEqualTo(650);
    }

    @Test
    @DisplayName("no gama requested (null) requires no certification")
    void sinGamaPedidaCertificacionEsNinguna() {
        assertThat(EstimadorDeConsumo.certificacionMinima(null)).isEqualTo(Certificacion.NINGUNA);
    }

    // ── BAJA/ECONOMICA: mismos numeros que "sin gama pedida" ─────────────

    @Test
    void bajaPisoEs450SinGpuY650ConGpu() {
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.BAJA, false)).isEqualTo(450);
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.BAJA, true)).isEqualTo(650);
    }

    @Test
    void bajaNoExigeCertificacion() {
        assertThat(EstimadorDeConsumo.certificacionMinima(Gama.BAJA)).isEqualTo(Certificacion.NINGUNA);
    }

    // ── MEDIA: 550/750, BRONZE ────────────────────────────────────────────

    @Test
    void mediaPisoEs550SinGpuY750ConGpu() {
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.MEDIA, false)).isEqualTo(550);
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.MEDIA, true)).isEqualTo(750);
    }

    @Test
    void mediaExigeBronze() {
        assertThat(EstimadorDeConsumo.certificacionMinima(Gama.MEDIA)).isEqualTo(Certificacion.BRONZE);
    }

    // ── ALTA: 750/1000, GOLD ──────────────────────────────────────────────

    @Test
    void altaPisoEs750SinGpuY1000ConGpu() {
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.ALTA, false)).isEqualTo(750);
        assertThat(EstimadorDeConsumo.wattsMinimos(Gama.ALTA, true)).isEqualTo(1000);
    }

    @Test
    void altaExigeGold() {
        assertThat(EstimadorDeConsumo.certificacionMinima(Gama.ALTA)).isEqualTo(Certificacion.GOLD);
    }
}
