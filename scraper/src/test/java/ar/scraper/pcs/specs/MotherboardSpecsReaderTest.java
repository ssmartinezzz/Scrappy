package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MotherboardSpecsReader} — marcaChip, tierChipset, wifi (T3b,
 * pc-builder-deep-taxonomy). Socket/DDR/formFactor coverage lives in
 * {@code TechSpecsParserTest} (phase 1) and is unchanged here.
 */
@DisplayName("MotherboardSpecsReader — marcaChip por socket, tierChipset, wifi")
class MotherboardSpecsReaderTest {

    private final MotherboardSpecsReader reader = new MotherboardSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsMotherboard() {
        assertThat(reader.categoria()).isEqualTo("Motherboard");
    }

    // ── marcaChip por socket ─────────────────────────────────────────────

    @Test
    void marcaChipAmdParaSocketAm() {
        assertThat(leer("Motherboard Asus TUF Gaming B650M-Plus WiFi AM5").marcaChip()).isEqualTo("AMD");
    }

    @Test
    void marcaChipIntelParaSocketLga() {
        assertThat(leer("Mother Gigabyte B860M E LGA1851 DDR5").marcaChip()).isEqualTo("INTEL");
    }

    @Test
    void marcaChipVacioSinSocketLegible() {
        assertThat(leer("Motherboard ECS H81H3-M4 DDR3").marcaChip()).isEmpty();
    }

    // ── tierChipset: X/Z=1, B=2, A/H=3, sin chipset=0 ────────────────────

    @Test
    void tierChipsetUnoParaXZ() {
        assertThat(leer("Motherboard Asrock X870 PRO RS DDR5 AM5").tierChipset()).isEqualTo(1);
        assertThat(leer("Motherboard MSI Z890 GAMING PLUS WIFI DDR5 1851").tierChipset()).isEqualTo(1);
    }

    @Test
    void tierChipsetDosParaB() {
        assertThat(leer("Mother MSI PRO B550M-B DDR4 AM4").tierChipset()).isEqualTo(2);
    }

    @Test
    void tierChipsetTresParaAyH() {
        assertThat(leer("Mother ASRock H610M-HDV DDR4").tierChipset()).isEqualTo(3);
        assertThat(leer("Motherboard Asrock A620M-HDV AM5").tierChipset()).isEqualTo(3);
    }

    @Test
    void tierChipsetCeroSinChipsetLegible() {
        assertThat(leer("Motherboard ECS H81H3-M4 DDR3").tierChipset()).isZero();
    }

    // ── wifi: afirmacion, no abstencion (D2 exception) ──────────────────

    @Test
    void wifiTruePalabraSuelta() {
        assertThat(leer("Mother Gigabyte B560M DS3H AC WiFi DDR4 S1200").wifi()).isTrue();
    }

    @Test
    void wifiTrueGuionAntesDelToken() {
        assertThat(leer("Motherboard Asus TUF Gaming B650M-Plus WiFi AM5").wifi()).isTrue();
    }

    @Test
    void wifiTrueSufijoPegado() {
        assertThat(leer("Motherboard MSI MAG X870E Tomahawk WiFi7").wifi()).isTrue();
    }

    @Test
    void wifiFalseCuandoElNombreNoLoDice() {
        assertThat(leer("Motherboard Asrock X870 PRO RS DDR5 AM5").wifi()).isFalse();
    }
}
