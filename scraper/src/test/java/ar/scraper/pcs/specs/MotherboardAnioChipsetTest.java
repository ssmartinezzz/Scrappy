package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MotherboardSpecsReader — anioChipset; B840 is A-class silicon")
class MotherboardAnioChipsetTest {

    private final MotherboardSpecsReader reader = new MotherboardSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @ParameterizedTest
    @CsvSource({
            "Motherboard Asus ROG Crosshair X870E Hero AM5, 2024",
            "Mother Gigabyte X670E Aorus Master AM5, 2022",
            "Motherboard MSI MAG B850 Tomahawk WiFi AM5, 2025",
            "Motherboard Asus TUF Gaming B650M-Plus WiFi AM5, 2022",
            "Mother Asrock A620M-HDV/M.2 AM5, 2023",
            "Mother MSI PRO B550M-B DDR4 AM4, 2020",
            "Motherboard MSI Z890 GAMING PLUS WIFI DDR5 1851, 2024",
            "Mother Asus Prime Z790-P DDR5 LGA1700, 2022",
            "Mother Gigabyte B760M DS3H DDR4, 2023",
            "Mother ASRock H610M-HDV DDR4, 2022",
            "Mother Gigabyte B860M E LGA1851 DDR5, 2025",
    })
    void anioDeLanzamientoDelChipset(String nombre, int anio) {
        assertThat(leer(nombre).anioChipset()).isEqualTo(anio);
    }

    @Test
    void sinChipsetLegibleAbstiene() {
        assertThat(leer("Motherboard ECS H81H3-M4 DDR3").anioChipset()).isZero();
    }

    @Test
    void b840EsTierA() {
        assertThat(leer("Mother Gigabyte B840M D2H AM5").tierChipset()).isEqualTo(3);
    }
}
