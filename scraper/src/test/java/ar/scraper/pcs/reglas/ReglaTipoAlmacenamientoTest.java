package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaTipoAlmacenamiento — candidato.tipoAlmacenamiento() vs el tipo pedido")
class ReglaTipoAlmacenamientoTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conTipo(TipoAlmacenamiento tipo) {
        return new TechSpecs("", "", "", 0, 0, "", ar.scraper.pcs.Gama.DESCONOCIDA,
                ar.scraper.pcs.Certificacion.NINGUNA, 0, tipo);
    }

    @Test
    @DisplayName("does not filter at all when no tipo was requested")
    void noFiltraCuandoNoSePidioTipo() {
        ReglaTipoAlmacenamiento regla = new ReglaTipoAlmacenamiento(null);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a candidate whose tipo matches the requested one")
    void noVetaCuandoCoincide() {
        ReglaTipoAlmacenamiento regla = new ReglaTipoAlmacenamiento(TipoAlmacenamiento.NVME);

        assertThat(regla.permite(conTipo(TipoAlmacenamiento.NVME), contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a candidate whose tipo differs from the requested one")
    void vetaCuandoDifiere() {
        ReglaTipoAlmacenamiento regla = new ReglaTipoAlmacenamiento(TipoAlmacenamiento.NVME);

        assertThat(regla.permite(conTipo(TipoAlmacenamiento.SSD), contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention — a candidate with no readable tecnologia (DESCONOCIDO) when a tipo was requested")
    void vetaPorAbstencionCuandoSePidioTipo() {
        ReglaTipoAlmacenamiento regla = new ReglaTipoAlmacenamiento(TipoAlmacenamiento.HDD);

        assertThat(regla.permite(conTipo(TipoAlmacenamiento.DESCONOCIDO), contexto)).isFalse();
    }

    @Test
    @DisplayName("motivo mentions the requested tipo")
    void motivoMencionaElTipoPedido() {
        ReglaTipoAlmacenamiento regla = new ReglaTipoAlmacenamiento(TipoAlmacenamiento.SSD);

        assertThat(regla.motivo()).contains("SSD");
    }
}
