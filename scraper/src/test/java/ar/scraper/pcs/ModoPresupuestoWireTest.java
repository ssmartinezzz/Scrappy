package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModoPresupuestoWireTest {

    @Test
    @DisplayName("blank/null/ahorrar parse to AHORRAR — the default")
    void blankAndAhorrarAreAhorrar() {
        assertThat(ModoPresupuestoWire.parse(null)).isEqualTo(ModoPresupuesto.AHORRAR);
        assertThat(ModoPresupuestoWire.parse("")).isEqualTo(ModoPresupuesto.AHORRAR);
        assertThat(ModoPresupuestoWire.parse("  ")).isEqualTo(ModoPresupuesto.AHORRAR);
        assertThat(ModoPresupuestoWire.parse("ahorrar")).isEqualTo(ModoPresupuesto.AHORRAR);
    }

    @Test
    @DisplayName("gastar maps to GASTAR_TODO, case-insensitive and trimmed")
    void gastarIsGastarTodo() {
        assertThat(ModoPresupuestoWire.parse("gastar")).isEqualTo(ModoPresupuesto.GASTAR_TODO);
        assertThat(ModoPresupuestoWire.parse(" GASTAR ")).isEqualTo(ModoPresupuesto.GASTAR_TODO);
    }

    @Test
    @DisplayName("any other value throws — callers map that to 400")
    void anyOtherValueThrows() {
        assertThatThrownBy(() -> ModoPresupuestoWire.parse("todo")).isInstanceOf(IllegalArgumentException.class);
    }
}
