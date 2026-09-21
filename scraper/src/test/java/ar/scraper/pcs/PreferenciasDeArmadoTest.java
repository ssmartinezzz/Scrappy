package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PreferenciasDeArmado — every field nullable means \"not requested\"; string/enum domains validated")
class PreferenciasDeArmadoTest {

    @Test
    @DisplayName("NINGUNA is all fields null")
    void ningunaEsTodoNull() {
        assertThat(PreferenciasDeArmado.NINGUNA.ddr()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.marcaCpu()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.marcaGpu()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.tipoAlmacenamiento()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.ramDual()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.wifi()).isNull();
    }

    @Test
    @DisplayName("accepts a fully-specified set of preferences")
    void aceptaTodoPedido() {
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(
                "DDR5", "AMD", "NVIDIA", TipoAlmacenamiento.NVME, true, true);

        assertThat(prefs.ddr()).isEqualTo("DDR5");
        assertThat(prefs.marcaCpu()).isEqualTo("AMD");
        assertThat(prefs.marcaGpu()).isEqualTo("NVIDIA");
        assertThat(prefs.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(prefs.ramDual()).isTrue();
        assertThat(prefs.wifi()).isTrue();
    }

    @Test
    @DisplayName("null is valid for every field")
    void nullEsValidoEnTodoCampo() {
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, null, null, null, null);

        assertThat(prefs).isEqualTo(PreferenciasDeArmado.NINGUNA);
    }

    @Test
    @DisplayName("FALSE in ramDual/wifi is accepted — it behaves like null, never like \"asked for no wifi\"")
    void falseEsValidoEnRamDualYWifi() {
        PreferenciasDeArmado prefs = new PreferenciasDeArmado(null, null, null, null, false, false);

        assertThat(prefs.ramDual()).isFalse();
        assertThat(prefs.wifi()).isFalse();
    }

    @Test
    @DisplayName("ddr accepts only DDR4/DDR5")
    void ddrRechazaValorFueraDeDominio() {
        assertThatThrownBy(() -> new PreferenciasDeArmado("DDR3", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("marcaCpu accepts only INTEL/AMD")
    void marcaCpuRechazaValorFueraDeDominio() {
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, "NVIDIA", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("marcaGpu accepts only NVIDIA/AMD")
    void marcaGpuRechazaValorFueraDeDominio() {
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, null, "INTEL", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tipoAlmacenamiento never accepts DESCONOCIDO — that's an abstention sentinel, not a pedible value")
    void tipoAlmacenamientoRechazaDesconocido() {
        assertThatThrownBy(() -> new PreferenciasDeArmado(
                null, null, null, TipoAlmacenamiento.DESCONOCIDO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
