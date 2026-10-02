package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoCooler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaTipoCoolerPedido — líquida vs aire, cuando el usuario lo pide (fase 9)")
class ReglaTipoCoolerPedidoTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conTipo(TipoCooler tipo) {
        return TechSpecs.builder().tipoCooler(tipo).build();
    }

    @Test
    void noFiltraCuandoNoSePidioTipo() {
        assertThat(new ReglaTipoCoolerPedido(null).permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    void permiteElTipoPedido() {
        assertThat(new ReglaTipoCoolerPedido(TipoCooler.LIQUIDO)
                .permite(conTipo(TipoCooler.LIQUIDO), contexto)).isTrue();
    }

    @Test
    void vetaElOtroTipo() {
        assertThat(new ReglaTipoCoolerPedido(TipoCooler.LIQUIDO)
                .permite(conTipo(TipoCooler.AIRE), contexto)).isFalse();
    }

    @Test
    void laAbstencionVetaCuandoSePidioTipo() {
        // D2. Acá además saca del pool a la pasta térmica y a los fans de
        // gabinete, que son justo lo que abstiene en esta categoría.
        assertThat(new ReglaTipoCoolerPedido(TipoCooler.AIRE)
                .permite(conTipo(TipoCooler.DESCONOCIDO), contexto)).isFalse();
    }

    @Test
    void elMotivoNombraElTipoPedido() {
        assertThat(new ReglaTipoCoolerPedido(TipoCooler.LIQUIDO).motivo()).contains("LIQUIDO");
    }
}
