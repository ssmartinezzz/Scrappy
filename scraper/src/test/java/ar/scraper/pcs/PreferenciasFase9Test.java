package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las cuatro preferencias que agrega la fase 9, en el dominio y en el cable:
 * {@code capacidadMinimaGb}, {@code tamanioGabinete}, {@code tipoCooler} y
 * {@code wattsMinimos}. Todas nullable = "no pedida" (D1 de la fase 7), y
 * ningún centinela de abstención es un valor pedible.
 */
@DisplayName("PreferenciasDeArmado + PreferenciasWire — las cuatro preferencias de la fase 9")
class PreferenciasFase9Test {

    @Test
    void ningunaNoPideNadaDeLoNuevo() {
        assertThat(PreferenciasDeArmado.NINGUNA.capacidadMinimaGb()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.tamanioGabinete()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.tipoCooler()).isNull();
        assertThat(PreferenciasDeArmado.NINGUNA.wattsMinimos()).isNull();
    }

    @Test
    void elConstructorDe6ArgsDeLaFase7DefaulteaLoNuevoANoPedido() {
        // CODE-2: todo caller de la forma anterior sigue compilando.
        PreferenciasDeArmado p = new PreferenciasDeArmado("DDR5", "AMD", "NVIDIA",
                TipoAlmacenamiento.NVME, Boolean.TRUE, Boolean.TRUE);

        assertThat(p.ddr()).isEqualTo("DDR5");
        assertThat(p.capacidadMinimaGb()).isNull();
        assertThat(p.tamanioGabinete()).isNull();
        assertThat(p.tipoCooler()).isNull();
        assertThat(p.wattsMinimos()).isNull();
    }

    @Test
    void unCentinelaDeAbstencionNoEsUnValorPedible() {
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, null, null, null, null, null,
                null, TamanioGabinete.DESCONOCIDO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, null, null, null, null, null,
                null, null, TipoCooler.DESCONOCIDO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unPisoNoPositivoNoEsUnPedido() {
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, null, null, null, null, null,
                0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreferenciasDeArmado(null, null, null, null, null, null,
                null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── cable ────────────────────────────────────────────────────────────

    @Test
    void elCableDelTamanioEsMiniMidFull() {
        assertThat(PreferenciasWire.parseTamanioGabinete("mid")).isEqualTo(TamanioGabinete.MID);
        assertThat(PreferenciasWire.parseTamanioGabinete("FULL")).isEqualTo(TamanioGabinete.FULL);
        assertThat(PreferenciasWire.parseTamanioGabinete("mini")).isEqualTo(TamanioGabinete.MINI);
        assertThat(PreferenciasWire.parseTamanioGabinete("")).isNull();
        assertThat(PreferenciasWire.parseTamanioGabinete(null)).isNull();
        assertThat(PreferenciasWire.wireTamanioGabinete(TamanioGabinete.MID)).isEqualTo("mid");
        assertThat(PreferenciasWire.wireTamanioGabinete(null)).isNull();
    }

    @Test
    void elCableDelCoolerEsLiquidoAire() {
        assertThat(PreferenciasWire.parseTipoCooler("liquido")).isEqualTo(TipoCooler.LIQUIDO);
        assertThat(PreferenciasWire.parseTipoCooler("aire")).isEqualTo(TipoCooler.AIRE);
        assertThat(PreferenciasWire.parseTipoCooler(" ")).isNull();
        assertThat(PreferenciasWire.wireTipoCooler(TipoCooler.AIRE)).isEqualTo("aire");
    }

    @Test
    void elCableNuncaEmiteUnCentinelaDeAbstencion() {
        assertThatThrownBy(() -> PreferenciasWire.wireTamanioGabinete(TamanioGabinete.DESCONOCIDO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenciasWire.wireTipoCooler(TipoCooler.DESCONOCIDO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elCableRechazaUnaPalabraQueNoEsDelVocabulario() {
        assertThatThrownBy(() -> PreferenciasWire.parseTamanioGabinete("torre"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenciasWire.parseTipoCooler("water"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseDe10ArgsArmaLasCuatroPreferenciasNuevas() {
        PreferenciasDeArmado p = PreferenciasWire.parse("ddr5", "amd", "nvidia", "nvme", true, true,
                1024, "mid", "liquido", 850);

        assertThat(p.capacidadMinimaGb()).isEqualTo(1024);
        assertThat(p.tamanioGabinete()).isEqualTo(TamanioGabinete.MID);
        assertThat(p.tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
        assertThat(p.wattsMinimos()).isEqualTo(850);
    }

    @Test
    void parseDe6ArgsSigueDandoLoMismoQueAntes() {
        assertThat(PreferenciasWire.parse(null, null, null, null, null, null))
                .isEqualTo(PreferenciasDeArmado.NINGUNA);
    }
}
