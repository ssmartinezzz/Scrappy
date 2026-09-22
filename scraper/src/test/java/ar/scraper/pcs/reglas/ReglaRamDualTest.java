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

@DisplayName("ReglaRamDual — candidato.modulos() >= 2 solo cuando se pidio TRUE; FALSE/null no filtran")
class ReglaRamDualTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conModulos(int modulos) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, modulos, false);
    }

    @Test
    @DisplayName("does not filter at all when ramDual is null")
    void noFiltraCuandoEsNull() {
        ReglaRamDual regla = new ReglaRamDual(null);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not filter at all when ramDual is FALSE — same as null, never \"asked for a single stick\"")
    void noFiltraCuandoEsFalse() {
        ReglaRamDual regla = new ReglaRamDual(false);

        assertThat(regla.permite(conModulos(1), contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a 2-module kit when TRUE was requested")
    void noVetaUnKitDualCuandoSePidioTrue() {
        ReglaRamDual regla = new ReglaRamDual(true);

        assertThat(regla.permite(conModulos(2), contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a single stick when TRUE was requested")
    void vetaUnSoloModuloCuandoSePidioTrue() {
        ReglaRamDual regla = new ReglaRamDual(true);

        assertThat(regla.permite(conModulos(1), contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention — a ram with no readable module count when TRUE was requested")
    void vetaPorAbstencionCuandoSePidioTrue() {
        ReglaRamDual regla = new ReglaRamDual(true);

        assertThat(regla.permite(conModulos(0), contexto)).isFalse();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(new ReglaRamDual(true).motivo()).isNotBlank();
    }
}
