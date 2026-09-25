package ar.scraper.pcs;

import ar.scraper.pcs.reglas.ReglaCompatibilidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CuotasDePresupuesto} — D5, pc-builder-homelab: el modo homelab
 * reparte el presupuesto con OTRA tabla de shares, mismo mecanismo que el
 * gamer (normalizada sobre los slots presentes, un slot fuera de tabla toma
 * el promedio).
 */
@DisplayName("CuotasDePresupuesto — Uso-aware shares (D5)")
class CuotasDePresupuestoTest {

    private static SlotDeArmado slot(String nombre) {
        return new SlotDeArmado(nombre, "cualquiera", List.<ReglaCompatibilidad>of(), (candidatos, ctx) -> candidatos.get(0));
    }

    @Test
    @DisplayName("para(slots) sin Uso sigue siendo GAMING — refactor contract")
    void paraSinUsoSigueSiendoGaming() {
        List<SlotDeArmado> slots = List.of(slot("mother"), slot("cpu"), slot("ram"));
        CuotasDePresupuesto gamingImplicito = CuotasDePresupuesto.para(slots);
        CuotasDePresupuesto gamingExplicito = CuotasDePresupuesto.para(slots, Uso.GAMING);
        assertThat(gamingImplicito.cuota("cpu", 1_000_000)).isEqualTo(gamingExplicito.cuota("cpu", 1_000_000));
    }

    @Test
    @DisplayName("homelab reparte distinto: datos se lleva más que sistema")
    void homelabRepartDistintoDatosMasQueSistema() {
        List<SlotDeArmado> slots = List.of(
                slot("mother"), slot("cpu"), slot("ram"), slot("gabinete"),
                slot("fuente"), slot("sistema"), slot("datos"));
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots, Uso.HOMELAB);
        double presupuesto = 1_000_000;
        assertThat(cuotas.cuota("datos", presupuesto)).isGreaterThan(cuotas.cuota("sistema", presupuesto));
    }

    @Test
    @DisplayName("homelab normaliza sobre los slots presentes: la suma de cuotas es el presupuesto entero")
    void homelabNormalizaSobreLosSlotsPresentes() {
        List<SlotDeArmado> slots = List.of(slot("mother"), slot("cpu"), slot("ram"), slot("datos"));
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots, Uso.HOMELAB);
        double presupuesto = 500_000;
        double suma = slots.stream().mapToDouble(s -> cuotas.cuota(s.nombre(), presupuesto)).sum();
        assertThat(suma).isCloseTo(presupuesto, within(0.01));
    }

    @Test
    @DisplayName("un slot fuera de la tabla homelab (minipc) toma la share media, no cero")
    void unSlotFueraDeLaTablaHomelabTomaLaShareMedia() {
        List<SlotDeArmado> slots = List.of(slot("minipc"), slot("datos"));
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots, Uso.HOMELAB);
        assertThat(cuotas.cuota("minipc", 1_000_000)).isGreaterThan(0);
    }
}
