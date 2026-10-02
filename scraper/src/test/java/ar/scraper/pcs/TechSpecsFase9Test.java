package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TechSpecs} — fase 9 (pc-builder-fine-grained-prefs T1) agrega
 * {@code tamanioGabinete} y {@code radiadorMm}. Los dos abstienen igual que
 * el resto del record: {@code DESCONOCIDO} y {@code 0}.
 */
@DisplayName("TechSpecs — tamanioGabinete + radiadorMm (fase 9 T1)")
class TechSpecsFase9Test {

    @Test
    void emptyAbstieneEnLosDosCamposNuevos() {
        assertThat(TechSpecs.EMPTY.tamanioGabinete()).isEqualTo(TamanioGabinete.DESCONOCIDO);
        assertThat(TechSpecs.EMPTY.radiadorMm()).isZero();
    }

    @Test
    void elConstructorCanonicoSeteaLosDosCamposNuevos() {
        TechSpecs t = TechSpecs.builder()
                .formFactor("ATX")
                .tipoCooler(TipoCooler.LIQUIDO)
                .tamanioGabinete(TamanioGabinete.FULL)
                .radiadorMm(360)
                .build();

        assertThat(t.tamanioGabinete()).isEqualTo(TamanioGabinete.FULL);
        assertThat(t.radiadorMm()).isEqualTo(360);
    }

    @Test
    void laFormaDe18ArgsDeFase8DefaulteaLosDosNuevosAAbstencion() {
        // Forma canonical de pc-builder-top-tier T1 (18 args): todo caller
        // existente sigue compilando sin tocarse — CODE-2.
        TechSpecs t = TechSpecs.builder()
                .socket("AM5")
                .ddr("DDR5")
                .formFactor("MATX")
                .gama(Gama.ALTA)
                .marcaChip("AMD")
                .generacion(9)
                .tierChipset(1)
                .modulos(2)
                .wifi(true)
                .tipoCooler(TipoCooler.AIRE)
                .nivel(9)
                .build();

        assertThat(t.tamanioGabinete()).isEqualTo(TamanioGabinete.DESCONOCIDO);
        assertThat(t.radiadorMm()).isZero();
    }
}
