package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaTamanioGabinete — el tamaño de torre pedido (fase 9, D1/D2)")
class ReglaTamanioGabineteTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conTamanio(TamanioGabinete tamanio) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false,
                TipoCooler.DESCONOCIDO, 0, tamanio, 0);
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
