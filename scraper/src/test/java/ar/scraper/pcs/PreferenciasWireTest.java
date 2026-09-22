package ar.scraper.pcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire↔domain mapping for the six technical preferences (pc-builder-deep-
 * taxonomy D8/T5c) — molde: {@link GamaWireTest}.
 */
class PreferenciasWireTest {

    @Test
    @DisplayName("every field blank/null parses to PreferenciasDeArmado.NINGUNA")
    void allBlankParsesToNinguna() {
        assertThat(PreferenciasWire.parse(null, "", null, "", null, null))
                .isEqualTo(PreferenciasDeArmado.NINGUNA);
    }

    @Test
    @DisplayName("ddr: DDR4/DDR5 parse case-insensitively; anything else throws")
    void ddrParsesOrThrows() {
        assertThat(PreferenciasWire.parseDdr("ddr4")).isEqualTo("DDR4");
        assertThat(PreferenciasWire.parseDdr("DDR5")).isEqualTo("DDR5");
        assertThat(PreferenciasWire.parseDdr(null)).isNull();
        assertThat(PreferenciasWire.parseDdr("")).isNull();
        assertThatThrownBy(() -> PreferenciasWire.parseDdr("ddr3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("marcaCpu: intel/amd parse case-insensitively; anything else throws")
    void marcaCpuParsesOrThrows() {
        assertThat(PreferenciasWire.parseMarcaCpu("intel")).isEqualTo("INTEL");
        assertThat(PreferenciasWire.parseMarcaCpu("AMD")).isEqualTo("AMD");
        assertThat(PreferenciasWire.parseMarcaCpu(null)).isNull();
        assertThatThrownBy(() -> PreferenciasWire.parseMarcaCpu("nvidia"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("marcaGpu: nvidia/amd parse case-insensitively; anything else throws")
    void marcaGpuParsesOrThrows() {
        assertThat(PreferenciasWire.parseMarcaGpu("nvidia")).isEqualTo("NVIDIA");
        assertThat(PreferenciasWire.parseMarcaGpu("AMD")).isEqualTo("AMD");
        assertThat(PreferenciasWire.parseMarcaGpu(null)).isNull();
        assertThatThrownBy(() -> PreferenciasWire.parseMarcaGpu("intel"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tipoAlmacenamiento: nvme/sata/hdd map to NVME/SSD/HDD, never DESCONOCIDO")
    void tipoAlmacenamientoParsesOrThrows() {
        assertThat(PreferenciasWire.parseTipoAlmacenamiento("nvme")).isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(PreferenciasWire.parseTipoAlmacenamiento("sata")).isEqualTo(TipoAlmacenamiento.SSD);
        assertThat(PreferenciasWire.parseTipoAlmacenamiento("HDD")).isEqualTo(TipoAlmacenamiento.HDD);
        assertThat(PreferenciasWire.parseTipoAlmacenamiento(null)).isNull();
        assertThatThrownBy(() -> PreferenciasWire.parseTipoAlmacenamiento("ssd"))
                .as("the wire word is 'sata', not the enum's own name")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenciasWire.parseTipoAlmacenamiento("desconocido"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse() builds the full record from the six wire values")
    void parseBuildsTheFullRecord() {
        PreferenciasDeArmado prefs = PreferenciasWire.parse("ddr5", "amd", "nvidia", "nvme", true, true);

        assertThat(prefs).isEqualTo(new PreferenciasDeArmado(
                "DDR5", "AMD", "NVIDIA", TipoAlmacenamiento.NVME, true, true));
    }

    @Test
    @DisplayName("wire() round-trips every known value")
    void wireRoundTrips() {
        assertThat(PreferenciasWire.wireDdr("DDR4")).isEqualTo("ddr4");
        assertThat(PreferenciasWire.wireMarcaCpu("INTEL")).isEqualTo("intel");
        assertThat(PreferenciasWire.wireMarcaGpu("NVIDIA")).isEqualTo("nvidia");
        assertThat(PreferenciasWire.wireTipoAlmacenamiento(TipoAlmacenamiento.NVME)).isEqualTo("nvme");
        assertThat(PreferenciasWire.wireTipoAlmacenamiento(TipoAlmacenamiento.SSD)).isEqualTo("sata");
        assertThat(PreferenciasWire.wireTipoAlmacenamiento(TipoAlmacenamiento.HDD)).isEqualTo("hdd");
    }

    @Test
    @DisplayName("wire() of null/not-requested is null")
    void wireOfNullIsNull() {
        assertThat(PreferenciasWire.wireDdr(null)).isNull();
        assertThat(PreferenciasWire.wireMarcaCpu(null)).isNull();
        assertThat(PreferenciasWire.wireMarcaGpu(null)).isNull();
        assertThat(PreferenciasWire.wireTipoAlmacenamiento(null)).isNull();
    }

    @Test
    @DisplayName("wireTipoAlmacenamiento rejects DESCONOCIDO — abstention is not a requestable value")
    void wireRejectsDesconocido() {
        assertThatThrownBy(() -> PreferenciasWire.wireTipoAlmacenamiento(TipoAlmacenamiento.DESCONOCIDO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
