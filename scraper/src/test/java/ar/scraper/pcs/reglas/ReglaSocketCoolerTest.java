package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaSocketCooler — cooler.socketsSoportados vs mother.socket")
class ReglaSocketCoolerTest {

    private final ReglaSocketCooler regla = new ReglaSocketCooler();

    @Test
    @DisplayName("vetoes when both sides parsed and the mother's socket is not in the cooler's list")
    void vetaCuandoAmbosParsearonYNoCruzan() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM5", "", "", 0, 0, ""));
        TechSpecs cooler = new TechSpecs("", "", "", 0, 0, "", ar.scraper.pcs.Gama.DESCONOCIDA,
                ar.scraper.pcs.Certificacion.NINGUNA, 0, ar.scraper.pcs.TipoAlmacenamiento.DESCONOCIDO,
                List.of("AM4"));

        assertThat(regla.permite(cooler, contexto)).isFalse();
    }

    @Test
    @DisplayName("does not veto when the mother's socket is in the cooler's list")
    void noVetaCuandoElSocketEstaEnLaLista() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM5", "", "", 0, 0, ""));
        TechSpecs cooler = new TechSpecs("", "", "", 0, 0, "", ar.scraper.pcs.Gama.DESCONOCIDA,
                ar.scraper.pcs.Certificacion.NINGUNA, 0, ar.scraper.pcs.TipoAlmacenamiento.DESCONOCIDO,
                List.of("AM4", "AM5"));

        assertThat(regla.permite(cooler, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the cooler names no socket at all")
    void abstieneCuandoElCoolerNoNombraSocket() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450)
                .conMother(new TechSpecs("AM5", "", "", 0, 0, ""));

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("abstains when the mother's socket did not parse")
    void abstieneCuandoLaMotherNoDeclaraSocket() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450).conMother(TechSpecs.EMPTY);
        TechSpecs cooler = new TechSpecs("", "", "", 0, 0, "", ar.scraper.pcs.Gama.DESCONOCIDA,
                ar.scraper.pcs.Certificacion.NINGUNA, 0, ar.scraper.pcs.TipoAlmacenamiento.DESCONOCIDO,
                List.of("AM4"));

        assertThat(regla.permite(cooler, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo explains what this rule vetoes")
    void motivoNoEstaVacio() {
        assertThat(regla.motivo()).isNotBlank();
    }
}
