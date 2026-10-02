package ar.scraper.pcs.specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Spec readers — the extraction several readers share")
class SpecsSharedExtractionTest {

    private static final CpuSpecsReader CPU = new CpuSpecsReader();
    private static final MotherboardSpecsReader MOTHER = new MotherboardSpecsReader();
    private static final GabineteSpecsReader GABINETE = new GabineteSpecsReader();

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "Procesador X am5, AM5", "Procesador X am4, AM4", "Procesador X am3, AM3",
            "Procesador X lga1851, LGA1851", "Procesador X 1851, LGA1851",
            "Procesador X lga1700, LGA1700", "Procesador X 1700, LGA1700",
            "Procesador X lga1200, LGA1200", "Procesador X 1200, LGA1200", "Procesador X s1200, LGA1200",
            "Procesador X lga1151, LGA1151", "Procesador X 1151, LGA1151", "Procesador X s1151, LGA1151"})
    @DisplayName("an explicit socket token wins, the same way for CPU and motherboard")
    void explicitSocketIsSharedByCpuAndMotherboard(String nombre, String socket) {
        assertThat(CPU.leer(Tokens.de(nombre)).socket()).isEqualTo(socket);
        assertThat(MOTHER.leer(Tokens.de(nombre)).socket()).isEqualTo(socket);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "Gabinete X itx, ITX", "Gabinete X matx, MATX", "Gabinete X m atx, MATX",
            "Gabinete X micro atx, MATX", "Gabinete X eatx, EATX", "Gabinete X e atx, EATX",
            "Gabinete X atx, ATX", "Gabinete X itx atx, ITX", "Gabinete X matx atx, MATX"})
    @DisplayName("the explicit form factor words read the same on a case and on a board without chipset")
    void explicitFormFactorIsSharedByCaseAndMotherboard(String nombre, String formFactor) {
        assertThat(GABINETE.leer(Tokens.de(nombre)).formFactor()).isEqualTo(formFactor);
        assertThat(MOTHER.leer(Tokens.de(nombre)).formFactor()).isEqualTo(formFactor);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({"Mini PC X 16Gb 480Gb, 16", "Mini PC X 480 Gb, 0", "Mini PC X 2x8Gb 120Gb, 120", "Mini PC X, 0"})
    @DisplayName("the first standalone NNGb token is the capacity")
    void firstStandaloneGbIsSharedByMiniPcRamAndGpu(String nombre, int gb) {
        assertThat(new MiniPcSpecsReader().leer(Tokens.de(nombre)).capacidadGb()).isEqualTo(gb);
        assertThat(new GpuSpecsReader().leer(Tokens.de(nombre)).capacidadGb()).isEqualTo(gb);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({"Ram X 16Gb, 16", "Ram X 2x8Gb, 16", "Ram X 0Gb 2x8Gb, 0", "Ram X 8Gb 2x8Gb, 8"})
    @DisplayName("RAM prefers the standalone NNGb token (even 0) over the kit multiplier")
    void ramPrefersStandaloneGb(String nombre, int gb) {
        assertThat(new RamSpecsReader().leer(Tokens.de(nombre)).capacidadGb()).isEqualTo(gb);
    }
}
