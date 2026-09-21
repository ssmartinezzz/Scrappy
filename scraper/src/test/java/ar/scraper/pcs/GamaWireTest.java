package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire↔domain mapping shared by the builder endpoint, the preferencia
 * endpoints and propose_pc's tool schema (CODE-6/DOC-1, pc-builder-gama T6).
 */
class GamaWireTest {

    @Test
    @DisplayName("blank/null parses to null — no gama requested")
    void blankOrNullParsesToNull() {
        assertThat(GamaWire.parse(null)).isNull();
        assertThat(GamaWire.parse("")).isNull();
        assertThat(GamaWire.parse("   ")).isNull();
    }

    @Test
    @DisplayName("the three wire values map to their tiers")
    void mapsTheThreeWireValues() {
        assertThat(GamaWire.parse("economica")).isEqualTo(Gama.BAJA);
        assertThat(GamaWire.parse("media")).isEqualTo(Gama.MEDIA);
        assertThat(GamaWire.parse("alta")).isEqualTo(Gama.ALTA);
    }

    @Test
    @DisplayName("case- and accent-insensitive")
    void caseAndAccentInsensitive() {
        assertThat(GamaWire.parse("ECONÓMICA")).isEqualTo(Gama.BAJA);
        assertThat(GamaWire.parse("Media")).isEqualTo(Gama.MEDIA);
        assertThat(GamaWire.parse(" alta ")).isEqualTo(Gama.ALTA);
    }

    @Test
    @DisplayName("any other value throws — callers map that to 400")
    void anyOtherValueThrows() {
        assertThatThrownBy(() -> GamaWire.parse("ultra")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("never maps to DESCONOCIDA — that is a parser abstention, not a requestable tier")
    void neverMapsToDesconocida() {
        assertThatThrownBy(() -> GamaWire.parse("desconocida")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("wire() round-trips the three known tiers")
    void wireRoundTripsKnownTiers() {
        assertThat(GamaWire.wire(Gama.BAJA)).isEqualTo("economica");
        assertThat(GamaWire.wire(Gama.MEDIA)).isEqualTo("media");
        assertThat(GamaWire.wire(Gama.ALTA)).isEqualTo("alta");
    }

    @Test
    @DisplayName("wire() rejects DESCONOCIDA")
    void wireRejectsDesconocida() {
        assertThatThrownBy(() -> GamaWire.wire(Gama.DESCONOCIDA)).isInstanceOf(IllegalArgumentException.class);
    }
}
