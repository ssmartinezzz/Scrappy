package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaDdrPedidaRam — la DDR pedida vs candidato.ddr()")
class ReglaDdrPedidaRamTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    @Test
    @DisplayName("does not filter at all when no ddr was requested")
    void noFiltraCuandoNoSePidioDdr() {
        ReglaDdrPedidaRam regla = new ReglaDdrPedidaRam(null);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a ram whose ddr matches the requested one")
    void noVetaCuandoCoincide() {
        ReglaDdrPedidaRam regla = new ReglaDdrPedidaRam("DDR5");
        TechSpecs ram = new TechSpecs("", "DDR5", "", 0, 0, "");

        assertThat(regla.permite(ram, contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a ram whose ddr differs from the requested one")
    void vetaCuandoDifiere() {
        ReglaDdrPedidaRam regla = new ReglaDdrPedidaRam("DDR5");
        TechSpecs ram = new TechSpecs("", "DDR4", "", 0, 0, "");

        assertThat(regla.permite(ram, contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention — a ram with no readable ddr when a ddr was requested")
    void vetaPorAbstencionCuandoSePidioDdr() {
        ReglaDdrPedidaRam regla = new ReglaDdrPedidaRam("DDR5");

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isFalse();
    }

    @Test
    @DisplayName("motivo mentions the requested ddr")
    void motivoMencionaLaDdrPedida() {
        ReglaDdrPedidaRam regla = new ReglaDdrPedidaRam("DDR4");

        assertThat(regla.motivo()).contains("DDR4");
    }
}
