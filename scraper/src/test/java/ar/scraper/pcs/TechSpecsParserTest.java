package ar.scraper.pcs;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("PC Builder")
@Feature("Compatibility")
@Story("TechSpecs parsing from product name")
@DisplayName("TechSpecsParser — socket/DDR/form-factor/watts/capacity from the name")
class TechSpecsParserTest {

    // ── null-safety ─────────────────────────────────────────────────────

    @Test
    void nullNombreAbstains() {
        assertThat(TechSpecsParser.parse(null, "CPU")).isEqualTo(TechSpecs.EMPTY);
    }

    @Test
    void blankNombreAbstains() {
        assertThat(TechSpecsParser.parse("   ", "Motherboard")).isEqualTo(TechSpecs.EMPTY);
    }

    @Test
    void nullCategoriaAbstains() {
        assertThat(TechSpecsParser.parse("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", null))
                .isEqualTo(TechSpecs.EMPTY);
    }

    @Test
    void unknownCategoriaAbstains() {
        assertThat(TechSpecsParser.parse("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", "Auriculares"))
                .isEqualTo(TechSpecs.EMPTY);
    }

    // ── Motherboard: explicit socket + chipset suffix -> form factor ──────

    @Test
    void motherboardExplicitAm5AndChipsetMSuffixGivesMatx() {
        var t = TechSpecsParser.parse("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardExplicitLga1851TokenAndChipsetMSuffix() {
        var t = TechSpecsParser.parse("Motherboard Gigabyte B860M E LGA1851 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1851");
        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardBareSocketNumberAndChipsetWithoutSuffixDefaultsToAtx() {
        // Z890 has no "M" suffix and there is no explicit "ATX" word either —
        // a recognized chipset with no size suffix is a full-size board.
        var t = TechSpecsParser.parse("Motherboard MSI Z890 GAMING PLUS WIFI DDR5 1851", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1851");
        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void motherboardExplicitAm4WithMSuffixChipset() {
        var t = TechSpecsParser.parse("Mother MSI PRO B550M-B DDR4 AM4", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM4");
        assertThat(t.ddr()).isEqualTo("DDR4");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardExplicitAm5NoSuffixChipsetDefaultsToAtx() {
        var t = TechSpecsParser.parse("Mother Asrock X870 PRO RS DDR5 AM5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void motherboardZ890MSuffixGivesMatxNotConfusedWithZ890Bare() {
        var t = TechSpecsParser.parse("Motherboard Gigabyte Z890M Gaming X LGA1851 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1851");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardChipsetDerivesAm5WithoutExplicitSocketToken() {
        var t = TechSpecsParser.parse("Motherboard Gigabyte B650 Aorus Elite AX DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void motherboardChipsetDerivesAm4FromMSuffixChipsetWithoutExplicitSocket() {
        var t = TechSpecsParser.parse("Motherboard Asus Prime B550M-A DDR4", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM4");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardChipsetDerivesLga1700FromMSuffixChipset() {
        var t = TechSpecsParser.parse("Motherboard MSI PRO B760M-A DDR4", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1700");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardIsuffixChipsetGivesItx() {
        var t = TechSpecsParser.parse("Motherboard Asrock B650I Lightning WiFi AM5 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.formFactor()).isEqualTo("ITX");
    }

    @Test
    void motherboardExplicitEatxWinsOverChipsetDefault() {
        var t = TechSpecsParser.parse("Motherboard Asus ROG Crosshair X670E Extreme EATX DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.formFactor()).isEqualTo("EATX");
    }

    @Test
    void motherboardUnknownChipsetAndNoExplicitKeywordsAbstainsSocketAndFormFactor() {
        var t = TechSpecsParser.parse("Motherboard ECS H81H3-M4 DDR3", "Motherboard");

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEqualTo("DDR3");
        assertThat(t.formFactor()).isEmpty();
    }

    @Test
    void motherboardBareDigitsInsideALongerSkuNeverMatchAsAnExplicitSocket() {
        // "sku21851034" contains the digits "1851" but is not the standalone
        // token "1851" — the chipset rule (b650 -> AM5) must win, not a
        // substring match of the LGA1851 socket hiding inside a longer number.
        var t = TechSpecsParser.parse("Motherboard ASRock B650 Steel Legend SKU21851034 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
    }

    @Test
    void motherboardExtremeChipsetSuffixDerivesSocketAndDefaultsToAtx() {
        // Deviation from the literal design table, found measuring the real catalog
        // (see pc-builder-specs.md): X670E/X870E "Extreme" tier boards have no "M"
        // suffix and often no explicit "ATX" word either, but they are full-size —
        // same default as a bare chipset, not an abstention.
        var t = TechSpecsParser.parse("Motherboard Asus Rog Strix X870e-e Gaming Neo AM5 DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void motherboardCompoundExtremeMicroSuffixGivesMatx() {
        // "B650EM"/"A620AM" — Extreme tier + Micro size combined in one token.
        // A naive 4-or-5-character chipset match misses these entirely.
        var t = TechSpecsParser.parse("Motherboard MSI PRO A620AM-B EVO WIFI DDR5", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM5");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    // ── Motherboard: old sockets (pc-builder-deep-taxonomy T2b) ────────────

    @Test
    void motherboardExplicitLga1200TokenAndChipsetMSuffix() {
        var t = TechSpecsParser.parse("Mother Asus Prime H510M-R R2.0 LGA1200", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1200");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardExplicitS1200TokenAndBareChipset() {
        var t = TechSpecsParser.parse("Mother ASRock H510 Pro BTC+ S1200 Mineria", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1200");
        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void motherboardChipsetB560MDerivesLga1200WithExplicitS1200Token() {
        var t = TechSpecsParser.parse("Mother Gigabyte B560M DS3H AC WiFi DDR4 S1200", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1200");
        assertThat(t.ddr()).isEqualTo("DDR4");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardChipsetDerivesLga1151FromBareChipsetNoSuffix() {
        var t = TechSpecsParser.parse("Motherboard Asrock H310M-HDV DDR4", "Motherboard");

        assertThat(t.socket()).isEqualTo("LGA1151");
        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void motherboardChipsetsZ390B365B360H370DeriveLga1151() {
        assertThat(TechSpecsParser.parse("Motherboard Asus Prime Z390-A", "Motherboard").socket())
                .isEqualTo("LGA1151");
        assertThat(TechSpecsParser.parse("Motherboard MSI B365M Pro", "Motherboard").socket())
                .isEqualTo("LGA1151");
        assertThat(TechSpecsParser.parse("Motherboard Gigabyte B360M DS3H", "Motherboard").socket())
                .isEqualTo("LGA1151");
        assertThat(TechSpecsParser.parse("Motherboard Asrock H370M Pro4", "Motherboard").socket())
                .isEqualTo("LGA1151");
    }

    @Test
    void motherboardChipsetsH410B460Z490Z590DeriveLga1200() {
        assertThat(TechSpecsParser.parse("Motherboard Asus Prime H410M-E", "Motherboard").socket())
                .isEqualTo("LGA1200");
        assertThat(TechSpecsParser.parse("Motherboard MSI B460M Pro", "Motherboard").socket())
                .isEqualTo("LGA1200");
        assertThat(TechSpecsParser.parse("Motherboard Gigabyte Z490 Aorus Elite", "Motherboard").socket())
                .isEqualTo("LGA1200");
        assertThat(TechSpecsParser.parse("Motherboard Asrock Z590 Steel Legend", "Motherboard").socket())
                .isEqualTo("LGA1200");
    }

    @Test
    void motherboardExplicitAm3TokenFromPlusSuffix() {
        var t = TechSpecsParser.parse("Motherboard ASRock 970 Extreme3 AM3+", "Motherboard");

        assertThat(t.socket()).isEqualTo("AM3");
    }

    // ── CPU: explicit tokens + derived from model number ───────────────────

    @Test
    void cpuExplicitAm5FromRyzen7000() {
        var t = TechSpecsParser.parse("Procesador Amd Ryzen 9 7900 Am5", "CPU");

        assertThat(t.socket()).isEqualTo("AM5");
    }

    @Test
    void cpuDerivedAm4FromRyzen5000ModelNumber() {
        var t = TechSpecsParser.parse("Procesador AMD Ryzen 5 5600", "CPU");

        assertThat(t.socket()).isEqualTo("AM4");
    }

    @Test
    void cpuDerivedAm5FromRyzen8000ModelNumber() {
        var t = TechSpecsParser.parse("Procesador AMD Ryzen 7 8700G", "CPU");

        assertThat(t.socket()).isEqualTo("AM5");
    }

    @Test
    void cpuDerivedAm4FromRyzen3000ModelNumber() {
        var t = TechSpecsParser.parse("Procesador AMD Ryzen 5 3600", "CPU");

        assertThat(t.socket()).isEqualTo("AM4");
    }

    @Test
    void cpuDerivedLga1700FromCoreI514xxxModelNumber() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 14400F", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1700");
    }

    @Test
    void cpuDerivedLga1851FromCoreUltra200Series() {
        var t = TechSpecsParser.parse("Procesador Intel Core Ultra 5 245K", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1851");
    }

    @Test
    void cpuExplicitLga1700Token() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 LGA1700", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1700");
    }

    @Test
    void cpuExplicitBareLga1851DigitsToken() {
        var t = TechSpecsParser.parse("Procesador Intel Core Ultra 7 Socket 1851", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1851");
    }

    // ── CPU: old sockets (pc-builder-deep-taxonomy T2b) ─────────────────────

    @Test
    void cpuExplicitLga1151Token() {
        var t = TechSpecsParser.parse(
                "Microprocesador Intel Core i5 9400 Coffeelake 4.1GHz 9MB LGA1151", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1151");
    }

    @Test
    void cpuDerivedLga1151FromCoreI9thGenModelNumber() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 9400", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1151");
    }

    @Test
    void cpuDerivedLga1151FromCoreI8thGenModelNumber() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 8400", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1151");
    }

    @Test
    void cpuDerivedLga1200FromCoreI10thGenModelNumber() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 10400F", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1200");
    }

    @Test
    void cpuDerivedLga1200FromCoreI11thGenModelNumber() {
        var t = TechSpecsParser.parse("Procesador Intel Core i7 11700K", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1200");
    }

    @Test
    void cpuExplicitS1200Token() {
        var t = TechSpecsParser.parse("Procesador Intel Core i5 S1200", "CPU");

        assertThat(t.socket()).isEqualTo("LGA1200");
    }

    @Test
    void cpuExplicitAm3TokenFromPlusSuffix() {
        var t = TechSpecsParser.parse("Micro AMD Phenom II X4 965 AM3+", "CPU");

        assertThat(t.socket()).isEqualTo("AM3");
    }

    @Test
    void cpuUnrecognizedModelAbstains() {
        var t = TechSpecsParser.parse("Procesador Intel Pentium G6400", "CPU");

        assertThat(t.socket()).isEmpty();
    }

    @Test
    void cpuOnlyFillsSocket() {
        var t = TechSpecsParser.parse("Procesador Amd Ryzen 9 7900 Am5", "CPU");

        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
    }

    // ── RAM: DDR + total capacity + DIMM/SODIMM ─────────────────────────

    @Test
    void ramStandaloneTotalGbWinsOverTheMultiplierInParens() {
        var t = TechSpecsParser.parse(
                "Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz CL40", "RAM");

        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.capacidadGb()).isEqualTo(64);
        assertThat(t.tipoMemoria()).isEqualTo("DIMM");
    }

    @Test
    void ramStandaloneTotalGbDdr4() {
        var t = TechSpecsParser.parse(
                "Memoria Corsair DDR4 32GB (2x16GB) 3200MHz Vengeance C16", "RAM");

        assertThat(t.ddr()).isEqualTo("DDR4");
        assertThat(t.capacidadGb()).isEqualTo(32);
    }

    @Test
    void ramSodimmDdr3() {
        var t = TechSpecsParser.parse("MEMORIA SODIMM 8GB DDR3 1600 HIKSEMI", "RAM");

        assertThat(t.ddr()).isEqualTo("DDR3");
        assertThat(t.capacidadGb()).isEqualTo(8);
        assertThat(t.tipoMemoria()).isEqualTo("SODIMM");
    }

    @Test
    void ramOnlyMultiplierNoStandaloneTotalMultipliesOut() {
        var t = TechSpecsParser.parse("Memoria Kingston Fury Beast DDR4 (2x16GB) 3600MHz", "RAM");

        assertThat(t.capacidadGb()).isEqualTo(32);
    }

    @Test
    void ramNoCapacityStatedAbstains() {
        var t = TechSpecsParser.parse("Memoria RAM Kingston Fury DDR5 6000MHz CL30", "RAM");

        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEqualTo("DIMM");
    }

    @Test
    void ramOnlyFillsDdrCapacityAndTipoMemoria() {
        var t = TechSpecsParser.parse("MEMORIA SODIMM 8GB DDR3 1600 HIKSEMI", "RAM");

        assertThat(t.socket()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
    }

    // ── Fuente: watts only, never form factor ───────────────────────────

    @Test
    void fuenteReads750WattsAndNeverSetsFormFactorDespiteAtxInTheName() {
        var t = TechSpecsParser.parse("Fuente Antec 750W 80 Plus Bronze ATX 3.1 PCIe 5.1", "Fuente");

        assertThat(t.watts()).isEqualTo(750);
        assertThat(t.formFactor()).isEmpty();
    }

    @Test
    void fuenteReads600WattsWithPlusSignAdjacent() {
        var t = TechSpecsParser.parse("Outlet Fuente Aerocool Cylon 600w 80+ Bronce", "Fuente");

        assertThat(t.watts()).isEqualTo(600);
    }

    @Test
    void fuenteReads550WattsWithoutMatchingTheModelCode() {
        // "FB550-LX" must not be misread as a wattage — only the "550w" token is.
        var t = TechSpecsParser.parse("Fuente LNZ 550W FB550-LX", "Fuente");

        assertThat(t.watts()).isEqualTo(550);
    }

    @Test
    void fuenteOnlyFillsWatts() {
        var t = TechSpecsParser.parse("Fuente Antec 750W 80 Plus Bronze ATX 3.1 PCIe 5.1", "Fuente");

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
    }

    // ── Gabinete: form factor only ──────────────────────────────────────

    @Test
    void gabineteMiniItx() {
        var t = TechSpecsParser.parse("Gabinete Cooler Master Masterbox NR200P White Mini ITX", "Gabinete");

        assertThat(t.formFactor()).isEqualTo("ITX");
    }

    @Test
    void gabineteNoFormFactorKeywordAbstains() {
        var t = TechSpecsParser.parse("Gabinete Thermaltake The Tower 300 TG x2 Fan Bumblebee", "Gabinete");

        assertThat(t.formFactor()).isEmpty();
    }

    @Test
    void gabineteEatx() {
        var t = TechSpecsParser.parse("Gabinete Corsair Obsidian 900D Full Tower E-ATX", "Gabinete");

        assertThat(t.formFactor()).isEqualTo("EATX");
    }

    @Test
    void gabineteMatx() {
        var t = TechSpecsParser.parse("Gabinete Cooler Master MasterBox Q300L Micro ATX", "Gabinete");

        assertThat(t.formFactor()).isEqualTo("MATX");
    }

    @Test
    void gabineteExplicitAtx() {
        var t = TechSpecsParser.parse("Gabinete Corsair 4000D Airflow ATX", "Gabinete");

        assertThat(t.formFactor()).isEqualTo("ATX");
    }

    @Test
    void gabineteOnlyFillsFormFactor() {
        var t = TechSpecsParser.parse("Gabinete Cooler Master Masterbox NR200P White Mini ITX", "Gabinete");

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
    }

    // ── abstain entirely: Cooler / Monitor / Almacenamiento ─────────────
    // La GPU salio de esta lista en pc-builder-gama: desde entonces llena
    // gama, y solo gama. Este test afirmaba TechSpecs.EMPTY y pasaba por
    // casualidad — su fixture es una RX 9060, que caia en DESCONOCIDA por el
    // defecto de numeracion Radeon que T3a corrigio.

    @Test
    void gpuSoloLlenaGama() {
        var t = TechSpecsParser.parse(
                "Placa de Video Gigabyte Radeon RX 9060 XT 8GB GDDR6 GAMING OC", "GPU");

        assertThat(t.gama()).isEqualTo(Gama.MEDIA);
        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.capacidadGb()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.certificacion()).isEqualTo(Certificacion.NINGUNA);
    }

    @Test
    void coolerAbstainsEntirelyInPhase1() {
        var t = TechSpecsParser.parse("Cooler Master Hyper 212 Black Edition", "Cooler");

        assertThat(t).isEqualTo(TechSpecs.EMPTY);
    }

    @Test
    void monitorAbstainsEntirelyInPhase1() {
        var t = TechSpecsParser.parse("Monitor LG 24 Pulgadas Full HD 75Hz", "Monitor");

        assertThat(t).isEqualTo(TechSpecs.EMPTY);
    }

    /**
     * Ya no se abstiene entera: {@code AlmacenamientoSpecsReader} (T3b) le da
     * tipo y capacidad, porque el ranking del slot almacenamiento los necesita.
     * El resto de los campos sigue abstenido, que es la intención real de este
     * test — igual que {@link #gpuSoloLlenaGama}.
     */
    @Test
    void almacenamientoSoloLlenaTipoYCapacidad() {
        var t = TechSpecsParser.parse("Disco SSD Kingston NV2 480GB M.2 NVMe", "Almacenamiento");

        assertThat(t.tipoAlmacenamiento()).isEqualTo(TipoAlmacenamiento.NVME);
        assertThat(t.capacidadGb()).isEqualTo(480);
        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
        assertThat(t.tipoMemoria()).isEmpty();
        assertThat(t.velocidadMhz()).isZero();
        assertThat(t.gama()).isEqualTo(Gama.DESCONOCIDA);
        assertThat(t.certificacion()).isEqualTo(Certificacion.NINGUNA);
    }
}
