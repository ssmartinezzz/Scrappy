package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leading noun beats a GPU / headset keyword: "Pasta Térmica ... Para CPU / Gpu" is thermal paste and "Titan 240 RX"
 * a Corsair cooler. Names are real dev rows (2026-11-18); V103 moves the stored rows with the same rule in SQL.
 */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("CategoryClassifier — el sustantivo líder gana sobre GPU / Auricular")
class TechLeadingNounBeforeGpuTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Water Cooler Corsair ICUE LINK TITAN 240 RX RGB 2X 240MM Black                | Cooler",
        "WATERCOOLER 360MM CORSAIR ICUE LINK TITAN 360 RX LCD CW-9061023-WW            | Cooler",
        "Watercooling Corsair Titan 360 Rx Icue Link Rgb                               | Cooler",
        "Water Cooling Corsair Icue Link Titan 240 Rx Lcd 2x Cpu Cooler 240mm          | Cooler",
        "Cooler CPU Corsair ICUE TITAN LINK 240 RX RGB Water Cooler                    | Cooler",
        "Pasta Térmica Corsair Tm30 De Alto Rendimiento Para Cpu / Gpu                 | Cooler",
        "Gabinete HYTE Y40 Snow White Vidrio Templado Vertical GPU Mount BULK - OUTLET | Gabinete",
        "Gabinete Be Quiet! LIGHT BASE 900FX Inc. Soporte Placa de video               | Gabinete",
        "Fuente 750W Corsair RM750e 80 PLUS Gold Full Modular Cable PCIe Para GPU      | Fuente",
        "Mouse Cougar Surpassion RX Wireless 2.4Ghz Black RGB PMW 3330                 | Mouse",
        "Mouse Logitech M196 Inalambrico BT Grafite                                    | Mouse",
        // an accessory for a card or a headset is neither one
        "Soporte para GPU Cooler Master Atlas ARGB VGA Bracket                         | Otros",
        "Rig de minería - Estructura para 6 GPU                                        | Otros",
        "SOPORTE PORTA AURICULARES TRUST CINTAR GXT 265 RGB                            | Otros",
        // a bare "arc" is not an Intel card, a bare "rx" not a Radeon
        "MADOKA MAGICA WRAITH ARC (PACK)                                               | Otros",
        // a console that lists "Headphone Jack" among its ports
        "My Arcade Micro Player Mini Arcade Machine: Galaga, Speaker, Headphone Jack, Battery | Consola",
        // a bundle that carries a headset is the bundle's lead peripheral
        "Combo Shinden Teclado Rkm75 He + Mouse M3 + Headset Hg103                     | Teclado",
        "Kit Gamer Razer On Bundle Mouse + Auriculares + Bungee                        | Mouse",
        "Reloj Star Wars Mandolarian Watch & Earbud Set                                | Reloj",
        // an upgrade combo is named by the seller, whatever it lists
        "Combo Actualización Kingston Ram Fury Beast 16GB (2X8GB) 3200 Mhz + SSD 1TB Kingston NV3 M.2 | Combo de Actualización",
        "Combo Actualizacion ASUS AMD Radeon RX 9070 XT 16GB Prime + Fuente ASUS 750W  | Combo de Actualización",
    })
    @DisplayName("la categoría sigue al sustantivo líder")
    void liderGana(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("", nombre.trim())).isEqualTo(esperada.trim());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Placa de Video Arktek R5 230 Cyclops 2GB GDDR3                | GPU",
        "PLACA DE VIDEO RADEON RX 580 8GB ARKTEK DUAL FAN              | GPU",
        "Placa de Video Sapphire Pulse RX 6600 8GB                     | GPU",
        "Placa de Video ASRock Intel Arc B580 12GB Challenger OC       | GPU",
        "Placa de Video ASRock Intel Arc A750 8GB                      | GPU",
        "Outlet Placa De Video Evga Rtx 3070 Xc3 Ultra 8gb             | GPU",
        "Auricular Redragon Zeus X H510 RGB                            | Auricular",
        "Auricular Genius HS-02B 3.5mm Single Plug                     | Auricular",
        "Switch Wired Chat Headset LVL1 Afterglow Black PDP            | Auricular",
    })
    @DisplayName("lo que sí es una placa o unos auriculares sigue siéndolo")
    void loLegitimoSigue(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("", nombre.trim())).isEqualTo(esperada.trim());
    }
}
