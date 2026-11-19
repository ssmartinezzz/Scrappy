package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Names are real rows scraped on 2026-11-19 from Samsung, Naldo, Cetrogar and Prestigio. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Celular — phones, phone-bound accessories and the generic tech that must stay where it is")
class CelularClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Celular Samsung Galaxy S25+ 12/256GB Mint",
        "Celular Motorola Razr 70 Ultra PANTONE 6.96\" cli 4\" 12GB 512GB Cocoa",
        "Celular Motorola G17 6.72\" 4GB 256GB PANTONE Bordeaux",
        "Celular TCL 605 5G 4GB 128GB Space Gray SO",
        "Celular Xiaomi Redmi Note 15 Pro 6,7\" 8 GB 256 GB Black (P6)",
        "Celular Samsung S26 Fe 256GB Graphite",
        "Telefono Celular Ulefone Armor X32 12GB 128GB Helio G91 Camara Vision Nocturna 48MP Bateri",
        "Reacondicionado iPhone 13 Apple 128 GB 6.1 Pulgadas 5G A15 Bionic",
        "Reacondicionado Smartphone Apple iPhone 15 Pro 256 GB 8 GB RAM",
        "Galaxy S26 Ultra",
        "Galaxy A57 5G Edición Exclusiva para Empresas",
        "Galaxy Z Flip7 FE",
        "Galaxy XCover7",
    })
    @DisplayName("phones are Celular, with or without the word celular in the name")
    void phones(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Celular");
        assertThat(subs.resolver(nombre, "Celular")).isEqualTo("Smartphone");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Funda Magsafe KVR PRO para iPhone 16 Pro Max|Funda",
        "Funda Super Silicone Samsung A07 para Galaxy A07|Funda",
        "Vidrio Templado iPhone Antiespia KVR PRO (Pack x2)|Protector",
        "Protector Lente de Cámara KVR PRO para Samsung Galaxy S|Protector",
        "Soporte para Celular con Ventosa - Groovy Stick|Soporte",
        "Cargador Portátil Magnético 10000mAh para iPhone MagSafe|Cargador",
        "Cable de carga rápida para iPhone Lightning 1m|Cable",
    })
    @DisplayName("an accessory the name ties to a phone is Celular, with its own sub")
    void accessoriesTiedToAPhone(String nombre, String sub) {
        assertThat(cat(nombre)).isEqualTo("Celular");
        assertThat(subs.resolver(nombre, "Celular")).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Cargador Mega Fox-Box 3 45w C/Cable Type C-C",
        "Cable Cargador USB Liquid Silicone - Groovy",
        "Cable Magnético Foxbox Type C A C 60w 1mt Negro",
        "Auriculares Inalámbricos Shokz OpenFit 2+ Grey",
        "Auricular Bluetooth InPods S41 - Groovy",
        "Smartwatch Garmin Forerunner 170 Black/Bk Yellow Band Gps",
        "Smartwatch Motorola Moto Watch 47 mm Negro Correa Volcanic Ash",
        "Localizador tag Motorola XT2445 Blueberry",
    })
    @DisplayName("generic tech with no tie to a phone stays out of Celular")
    void genericTechStays(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Celular");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Galaxy Tab S12 Ultra Gray 256GB - Incluye funda con teclado + S Pen|Tablet",
        "Galaxy Tab A11|Tablet",
        "Smartwatch Samsung Galaxy Watch6 Classic 47mm Silver|Reloj",
        "Smartwatch Samsung Galaxy Fit3 Silver|Reloj",
        "Galaxy Buds3 Pro|Auricular",
    })
    @DisplayName("other Galaxy devices are not phones")
    void otherGalaxyDevices(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Auricular Inalambrico Xiaomi Redmi Buds 6 Active Blue",
        "Reloj Smartwatch Xiaomi Redmi Watch 5 Active Black",
        "Tablet Xiaomi Redmi Pad 2 11\" 2.5K Octa-Core 8GB 256Gb Green WIFI",
        "Tablet 8.7 Samsung Galaxy A9 SM-X110 64Gb 4Gb Graphite",
        "Teléfono Celular Interactivo de Juguete Stitch con Luces y Sonidos",
        "Celulares En Juego",
        "Soporte Redragon Galaxy Para Joystick y Cables 40Cm",
        "Rosalina's Storybook - Super Mario Galaxy Book",
    })
    @DisplayName("look-alikes from the dev catalog (Redmi Buds/Watch/Pad, toy phones, a board game) are not phones")
    void lookAlikes(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Celular");
    }

    @Test
    @DisplayName("an accessory for a Galaxy Tab is not a phone accessory")
    void tabletAccessoryIsNotAPhoneAccessory() {
        assertThat(cat("Funda para exterior Galaxy Tab S9 FE+")).isNotEqualTo("Celular");
    }

    @Test
    @DisplayName("a phone row with no clear kind keeps an empty sub")
    void unknownKindHasNoSub() {
        assertThat(subs.resolver("Smart Tag", "Celular")).isEmpty();
        assertThat(subs.resolver("Botón de encendido S23 Ultra", "Celular")).isEqualTo("Repuesto");
    }
}
