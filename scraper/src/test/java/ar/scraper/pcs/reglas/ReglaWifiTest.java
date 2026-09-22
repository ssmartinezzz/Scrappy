package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaWifi — candidato.wifi() solo cuando se pidio TRUE; wifi=false es afirmacion, no abstencion (D2)")
class ReglaWifiTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conWifi(boolean wifi) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, wifi);
    }

    @Test
    @DisplayName("does not filter at all when wifi is null")
    void noFiltraCuandoEsNull() {
        ReglaWifi regla = new ReglaWifi(null);

        assertThat(regla.permite(conWifi(false), contexto)).isTrue();
    }

    @Test
    @DisplayName("does not filter at all when wifi is FALSE — same as null, never \"asked for no wifi\"")
    void noFiltraCuandoEsFalse() {
        ReglaWifi regla = new ReglaWifi(false);

        assertThat(regla.permite(conWifi(false), contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a candidate that declares wifi when TRUE was requested")
    void noVetaCuandoDeclaraWifi() {
        ReglaWifi regla = new ReglaWifi(true);

        assertThat(regla.permite(conWifi(true), contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a candidate whose wifi=false (an assertion, not abstention) when TRUE was requested")
    void vetaCuandoNoDeclaraWifi() {
        ReglaWifi regla = new ReglaWifi(true);

        assertThat(regla.permite(conWifi(false), contexto)).isFalse();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(new ReglaWifi(true).motivo()).isNotBlank();
    }
}
