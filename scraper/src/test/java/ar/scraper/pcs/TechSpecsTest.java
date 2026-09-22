package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TechSpecs} — T3a adds marcaChip/generacion/tierChipset/modulos/wifi.
 * {@code wifi} is the one field where {@code false} is an assertion, not
 * abstención (D2 exception) — everything else here follows the same
 * abstention-last convention as the rest of the record.
 */
@DisplayName("TechSpecs — marcaChip/generacion/tierChipset/modulos/wifi (pc-builder-deep-taxonomy T3a)")
class TechSpecsTest {

    @Test
    void emptyAbstainsOnTheFiveNewFields() {
        assertThat(TechSpecs.EMPTY.marcaChip()).isEmpty();
        assertThat(TechSpecs.EMPTY.generacion()).isZero();
        assertThat(TechSpecs.EMPTY.tierChipset()).isZero();
        assertThat(TechSpecs.EMPTY.modulos()).isZero();
        assertThat(TechSpecs.EMPTY.wifi()).isFalse();
    }

    @Test
    void canonicalConstructorSetsTheFiveNewFields() {
        TechSpecs t = new TechSpecs("AM5", "DDR5", "MATX", 0, 0, "", Gama.ALTA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 5, 1, 2, true);

        assertThat(t.marcaChip()).isEqualTo("AMD");
        assertThat(t.generacion()).isEqualTo(5);
        assertThat(t.tierChipset()).isEqualTo(1);
        assertThat(t.modulos()).isEqualTo(2);
        assertThat(t.wifi()).isTrue();
    }

    @Test
    void elevenArgCompatConstructorDefaultsTheFiveNewFieldsToAbstencion() {
        // Forma pre-T3a (11 args, canonical antes de este cambio): todo
        // caller existente sigue compilando sin tocarse — CODE-2.
        TechSpecs t = new TechSpecs("AM4", "DDR4", "ATX", 0, 0, "", Gama.MEDIA, Certificacion.GOLD,
                3200, TipoAlmacenamiento.NVME, List.of("AM4"));

        assertThat(t.marcaChip()).isEmpty();
        assertThat(t.generacion()).isZero();
        assertThat(t.tierChipset()).isZero();
        assertThat(t.modulos()).isZero();
        assertThat(t.wifi()).isFalse();
    }
}
