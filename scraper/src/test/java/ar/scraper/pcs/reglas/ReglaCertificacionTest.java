package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaCertificacion — fuente.certificacion() vs la certificacion minima de la gama pedida")
class ReglaCertificacionTest {

    private final ReglaCertificacion regla = new ReglaCertificacion();

    private TechSpecs fuente(Certificacion cert) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, cert);
    }

    @Test
    @DisplayName("vetoes when the psu's certification is below the requested minimum")
    void vetaCuandoNoAlcanzaElMinimo() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD);

        assertThat(regla.permite(fuente(Certificacion.BRONZE), contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when the psu's certification meets the requested minimum")
    void noVetaCuandoAlcanzaElMinimo() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD);

        assertThat(regla.permite(fuente(Certificacion.GOLD), contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto when the psu's certification exceeds the requested minimum")
    void noVetaCuandoSuperaElMinimo() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD);

        assertThat(regla.permite(fuente(Certificacion.TITANIUM), contexto)).isTrue();
    }

    @Test
    @DisplayName("NINGUNA follows the house's NORMAL abstention policy: abstention does NOT veto, unlike ReglaGama")
    void noVetaPorAbstencionAunConMinimoExigido() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(750, Gama.ALTA, Certificacion.GOLD);

        assertThat(regla.permite(fuente(Certificacion.NINGUNA), contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto anything when no gama was requested (minimum is NINGUNA)")
    void noVetaCuandoNoSePidioGama() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(regla.permite(fuente(Certificacion.NINGUNA), contexto)).isTrue();
        assertThat(regla.permite(fuente(Certificacion.WHITE), contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
