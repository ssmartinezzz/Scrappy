package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EjesTecnicos} — los dos ejes que profundiza la fase 9 (D6):
 * el radiador dentro del cooler y los watts dentro de la fuente. Los dos
 * respetan la regla de siempre: la abstención va ÚLTIMA (D7).
 */
@DisplayName("EjesTecnicos — radiador (COOLER) y watts (FUENTE), fase 9")
class EjesTecnicosFase9Test {

    private static TechSpecs cooler(TipoCooler tipo, int radiadorMm) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, tipo, 0,
                TamanioGabinete.DESCONOCIDO, radiadorMm);
    }

    private static TechSpecs fuente(Certificacion cert, int watts) {
        return new TechSpecs("", "", "", watts, 0, "", Gama.DESCONOCIDA, cert);
    }

    // ── COOLER: tipo → radiador desc ─────────────────────────────────────

    @Test
    void elTipoSigueMandandoPorEncimaDelRadiador() {
        // Una AIO sin radiador legible le gana igual a un cooler de aire:
        // el eje nuevo es el SEGUNDO, no el primero.
        assertThat(EjesTecnicos.COOLER.compare(cooler(TipoCooler.LIQUIDO, 0), cooler(TipoCooler.AIRE, 0)))
                .isNegative();
    }

    @Test
    void entreDosLiquidasGanaElRadiadorMasGrande() {
        assertThat(EjesTecnicos.COOLER.compare(cooler(TipoCooler.LIQUIDO, 360), cooler(TipoCooler.LIQUIDO, 240)))
                .isNegative();
    }

    @Test
    void unaLiquidaSinRadiadorLegibleVaDespuesDeUnaQueLoDeclara() {
        // D7: 0 es abstención y va última dentro de su escalón, nunca primera.
        assertThat(EjesTecnicos.COOLER.compare(cooler(TipoCooler.LIQUIDO, 0), cooler(TipoCooler.LIQUIDO, 240)))
                .isPositive();
    }

    @Test
    void elRadiadorNoReordenaCoolersDeAire() {
        assertThat(EjesTecnicos.COOLER.compare(cooler(TipoCooler.AIRE, 0), cooler(TipoCooler.AIRE, 0))).isZero();
    }

    // ── FUENTE: certificación → watts desc ───────────────────────────────

    @Test
    void laCertificacionSigueMandandoPorEncimaDeLosWatts() {
        // Una GOLD de 650W le gana a una NINGUNA de 1200W: el eje nuevo es
        // el SEGUNDO. Antes de esto la fuente rankeaba por certificación sola
        // y entre dos GOLD ganaba la más barata, que es la de menos watts.
        assertThat(EjesTecnicos.FUENTE.compare(fuente(Certificacion.GOLD, 650), fuente(Certificacion.NINGUNA, 1200)))
                .isNegative();
    }

    @Test
    void entreDosGoldGanaLaDeMasWatts() {
        assertThat(EjesTecnicos.FUENTE.compare(fuente(Certificacion.GOLD, 850), fuente(Certificacion.GOLD, 650)))
                .isNegative();
    }

    @Test
    void unaFuenteSinWattsLegiblesVaDespuesDeUnaQueLosDeclara() {
        // D7 otra vez: 7 de 354 filas del catálogo no declaran watts.
        assertThat(EjesTecnicos.FUENTE.compare(fuente(Certificacion.GOLD, 0), fuente(Certificacion.GOLD, 550)))
                .isPositive();
    }
}
