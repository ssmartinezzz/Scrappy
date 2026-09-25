package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire↔domain mapping for {@code uso} (CODE-6/DOC-1, pc-builder-homelab
 * D3). Unlike {@link GamaWire}, blank/null is a real default (GAMING), not
 * "not requested" — {@link Uso} has no null/abstention state.
 */
class UsoWireTest {

    @Test
    @DisplayName("blank/null parses to GAMING — the default, not \"not requested\"")
    void blankOrNullParsesToGaming() {
        assertThat(UsoWire.parse(null)).isEqualTo(Uso.GAMING);
        assertThat(UsoWire.parse("")).isEqualTo(Uso.GAMING);
        assertThat(UsoWire.parse("   ")).isEqualTo(Uso.GAMING);
    }

    @Test
    @DisplayName("the two wire values map to their Uso")
    void mapsTheTwoWireValues() {
        assertThat(UsoWire.parse("gaming")).isEqualTo(Uso.GAMING);
        assertThat(UsoWire.parse("homelab")).isEqualTo(Uso.HOMELAB);
    }

    @Test
    @DisplayName("case-insensitive, trims whitespace")
    void caseInsensitiveTrimsWhitespace() {
        assertThat(UsoWire.parse("HOMELAB")).isEqualTo(Uso.HOMELAB);
        assertThat(UsoWire.parse(" Gaming ")).isEqualTo(Uso.GAMING);
    }

    @Test
    @DisplayName("any other value throws — callers map that to 400")
    void anyOtherValueThrows() {
        assertThatThrownBy(() -> UsoWire.parse("servidor")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("wire() round-trips the two values")
    void wireRoundTripsTheTwoValues() {
        assertThat(UsoWire.wire(Uso.GAMING)).isEqualTo("gaming");
        assertThat(UsoWire.wire(Uso.HOMELAB)).isEqualTo("homelab");
    }
}
