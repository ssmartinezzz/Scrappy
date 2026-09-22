package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        TechSpecs t = new TechSpecs("", "", "ATX", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false,
                TipoCooler.LIQUIDO, 0, TamanioGabinete.FULL, 360);

        assertThat(t.tamanioGabinete()).isEqualTo(TamanioGabinete.FULL);
        assertThat(t.radiadorMm()).isEqualTo(360);
    }

    @Test
    void laFormaDe18ArgsDeFase8DefaulteaLosDosNuevosAAbstencion() {
        // Forma canonical de pc-builder-top-tier T1 (18 args): todo caller
        // existente sigue compilando sin tocarse — CODE-2.
        TechSpecs t = new TechSpecs("AM5", "DDR5", "MATX", 0, 0, "", Gama.ALTA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 9, 1, 2, true,
                TipoCooler.AIRE, 9);

        assertThat(t.tamanioGabinete()).isEqualTo(TamanioGabinete.DESCONOCIDO);
        assertThat(t.radiadorMm()).isZero();
    }
}
