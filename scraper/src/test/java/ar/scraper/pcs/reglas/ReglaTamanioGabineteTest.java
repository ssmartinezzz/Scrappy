package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaTamanioGabinete — el tamaño de torre pedido (fase 9, D1/D2)")
class ReglaTamanioGabineteTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conTamanio(TamanioGabinete tamanio) {
        return TechSpecs.builder().tamanioGabinete(tamanio).build();
    }

    @Test
    void noFiltraCuandoNoSePidioTamanio() {
        assertThat(new ReglaTamanioGabinete(null).permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    void permiteElTamanioPedido() {
        assertThat(new ReglaTamanioGabinete(TamanioGabinete.MID)
                .permite(conTamanio(TamanioGabinete.MID), contexto)).isTrue();
    }

    @Test
    void vetaOtroTamanio() {
        assertThat(new ReglaTamanioGabinete(TamanioGabinete.MID)
                .permite(conTamanio(TamanioGabinete.FULL), contexto)).isFalse();
    }

    @Test
    void laAbstencionVetaCuandoSePidioTamanio() {
        // D2, y acá es CARO: 576 de 622 gabinetes del catálogo abstienen
        // (medido 2026-09-22). Es el precio de que "mid-tower" signifique algo.
        assertThat(new ReglaTamanioGabinete(TamanioGabinete.MID)
                .permite(conTamanio(TamanioGabinete.DESCONOCIDO), contexto)).isFalse();
    }

    @Test
    void elMotivoNombraElTamanioPedido() {
        assertThat(new ReglaTamanioGabinete(TamanioGabinete.FULL).motivo()).contains("FULL");
    }
}
