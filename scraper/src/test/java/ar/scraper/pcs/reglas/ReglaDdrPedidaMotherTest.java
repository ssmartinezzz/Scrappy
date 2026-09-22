package ar.scraper.pcs.reglas;

import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaDdrPedidaMother — la DDR pedida vs la de un candidato a motherboard (declarada o derivada del socket)")
class ReglaDdrPedidaMotherTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    @Test
    @DisplayName("does not filter at all when no ddr was requested")
    void noFiltraCuandoNoSePidioDdr() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother(null);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a candidate whose own declared ddr matches the requested one")
    void noVetaCuandoLaDdrDeclaradaCoincide() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother("DDR5");
        TechSpecs mother = new TechSpecs("", "DDR5", "", 0, 0, "");

        assertThat(regla.permite(mother, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a candidate whose ddr is only derivable from socket and matches")
    void noVetaCuandoLaDdrDerivadaDelSocketCoincide() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother("DDR5");
        TechSpecs mother = new TechSpecs("AM5", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a candidate whose ddr (declared or derived) differs from the requested one")
    void vetaCuandoLaDdrDifiere() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother("DDR5");
        TechSpecs mother = new TechSpecs("AM4", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention — a mother whose ddr can't be read nor derived (e.g. LGA1700) when a ddr was requested")
    void vetaPorAbstencionCuandoSePidioDdr() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother("DDR5");
        TechSpecs mother = new TechSpecs("LGA1700", "", "", 0, 0, "");

        assertThat(regla.permite(mother, contexto)).isFalse();
    }

    @Test
    @DisplayName("motivo mentions the requested ddr")
    void motivoMencionaLaDdrPedida() {
        ReglaDdrPedidaMother regla = new ReglaDdrPedidaMother("DDR5");

        assertThat(regla.motivo()).contains("DDR5");
    }
}
