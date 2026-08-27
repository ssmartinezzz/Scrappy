package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * En la taxonomía de categorías, el ESPACIO es el word boundary.
 *
 * <p>{@link GarmentTaxonomy#anyMatch} es un {@code contains()} pelado sobre un
 * texto que {@code CategoryClassifier} ya padeó con espacios. Un keyword
 * declarado {@code "ram "} en vez de {@code " ram "} matchea adentro de
 * cualquier palabra terminada en "ram", y nadie se entera: el producto entra al
 * catálogo con la categoría equivocada <b>y con la distribución de precios de
 * otra categoría</b>, que es de lo que se alimenta el pipeline ML.</p>
 *
 * <p><b>Los nombres de abajo no son inventados</b>: salieron de las 16.830 filas
 * activas de la base, con la categoría equivocada que tenían. El barrido que los
 * encontró es mecánico y se puede repetir: buscar en los arrays {@code KW_*} los
 * keywords que terminan en espacio pero no empiezan con uno, y contar los
 * nombres reales donde el token aparece como substring pero no como palabra.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("short keywords need a word boundary")
@DisplayName("CategoryClassifier — un keyword corto no se come palabras que lo contienen")
class KeywordBoundaryContaminationTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "ram " se comía Dram / Sram / Ingram / Monogram — 8 filas reales en RAM
        "'Camisa Manga Larga Lino Dram Militar',                Camisa",
        "'Pastillas De Freno Sram Orgánica Alum',               Otros",
        "'Calza [ Ingram ] Negro',                              Calza",
        "'Bikini Monogram',                                     Malla",
        // ...y la RAM de verdad sigue siendo RAM
        "'Memoria Ram Kingston Fury 8GB DDR4 3200MHz',          RAM",
        "'Memoria RAM 16GB DDR5',                               RAM",
    })
    @DisplayName("'ram' no se come Dram, Sram, Ingram ni Monogram")
    void ramNoContaminaPalabrasQueTerminanEnRam(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "malla" se comía "Mallado"; "bano " se comía "Urbano" y "Habano"
        "'Buzo Topper Hoodie Urbano Chill Rtc',                 Buzo",
        "'Pantalon Topper Urbano Vivo',                         Pantalón",
        "'Shorts Topper Roy Urbano',                            Short",
        "'Zapatos Nauta - Rodeo Habano',                        Otros",
        // ...y la malla de verdad sigue siendo Malla
        "'Malla Enteriza Negra',                                Malla",
        "'Mallas de Baño Hombre',                               Malla",
        "'Short de Baño Hombre',                                Malla",
    })
    @DisplayName("'malla' no se come 'mallado' y 'bano' no se come 'urbano'")
    void mallaYBanoNoContaminan(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "bra " se comía "Hembra": los adaptadores HDMI entraban como corpiños
        "'Adaptador Hdmi Hembra A Mini Hdmi',                   Cable",
        "'Antiparras Arena Cobra Core Swipe Mirror',            Lentes",
        "'Toalla adidas de Microfibra Magnética Players Cart',  Otros",
        // ...y el corpiño de verdad sigue siendo Corpino
        "'Sports Bra Negro de Mujer',                           Corpino",
        "'Bralette Deportivo Rosa',                             Corpino",
    })
    @DisplayName("'bra' no se come Hembra, Cobra ni Microfibra")
    void braNoContaminaHembraNiCobra(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // Un cooler de CPU no es un CPU. Eran 321 de las 646 filas de la
        // categoría: la MITAD, a un orden de magnitud de precio del procesador
        // que decían ser. Padear "cpu " no alcanzaba — hacía falta que Cooler
        // existiera y corriera antes.
        "'Cooler CPU ID-Cooling SE-214-XT ARGB',                Cooler",
        "'Cpu Cooler Aerocool Verkho A',                        Cooler",
        "'CPU Cooler Cooler Master Hyper 212 Spectrum V3 ARGB',  Cooler",
        "'Cooler CPU Cougar Poseidon Ultra ARGB 360 Water Cooler',  Cooler",
        // ...y el procesador de verdad sigue siendo CPU, incluso diciendo "sin cooler"
        "'Procesador AMD Ryzen 9 9900X3D 5.5GHz Turbo AM5',     CPU",
        "'Procesador Intel Core i5 12400F',                     CPU",
    })
    @DisplayName("Un cooler de CPU es un Cooler, no un CPU")
    void coolerDeCpuNoEsCpu(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "shirt" pelado vivía en KW_CAMISA, que corre ANTES de KW_REMERA:
        // toda remera en inglés terminaba archivada como camisa formal.
        "'MKF Adjustable T-shirt',                              Remera",
        "'Remera Fila Round Neck T Shirt',                      Remera",
        "'MKF Compression Shirt Men',                           Remera",
        "'Over Gym Shirt',                                      Remera",
        "'Gash Shirt',                                          Remera",
        "'Tournament Shirt',                                    Remera",
        // ...y la camisa de verdad, la que se nombra a sí misma, sigue siendo Camisa
        "'Camisa Manga Larga Oxford Celeste',                   Camisa",
        "'Lumberjack Shirt',                                    Camisa",
        "'Flannel Shirt Cuadros',                               Camisa",
        "'Chambray Shirt Celeste',                              Camisa",
        // OJO, y es PREVIO a este cambio: "Denim Shirt" NO da Camisa sino
        // Conjunto. "denim" está en KW_JEAN (piernas) y la camisa está en el
        // bloque torso, así que el detector de combo de ADR-4 dispara antes que
        // cualquier bloque de prenda. Pasaba igual cuando "denim shirt" vivía en
        // KW_CAMISA. Se deja fijado para que no se lea como regresión de acá.
        "'Denim Shirt Azul',                                    Conjunto",
    })
    @DisplayName("Un 'shirt' sin calificador es una remera; la camisa se nombra")
    void shirtDeportivaNoEsCamisaFormal(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "cap " se comía "X60cap"; "hat " se comía "That"
        "'Buti Smart Acido Butírico X60cap Cetogenico Leguilab',        Otros",
        "'Zapatillas adidas de Básquet Believe That 1 Unisex',          Zapatilla",
        // ...y la gorra de verdad sigue siendo Gorra
        "'Gorra adidas Trucker Negra',                                  Gorra",
        "'Baseball Cap Negra',                                          Gorra",
    })
    @DisplayName("'cap' no se come X60cap y 'hat' no se come That")
    void capYHatNoContaminan(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "rx " se comía Merx / Arx / HyperX y archivaba todo eso como GPU
        "'Memoria Ram Merx Ddr3 8gb 1600mhz 1.35v',             RAM",
        "'GABINETE GAMER AUREOX SKOLL ARX 200G',                Gabinete",
        "'Auriculares HyperX Cloud Stinger 2 White',            Auricular",
        "'Zapatillas Salomon Drx Defy',                         Zapatilla",
        // ...y la placa de video de verdad sigue siendo GPU
        "'Placa de Video Nvidia RTX 4060 8GB',                  GPU",
        "'GPU Radeon RX 7600 XT',                               GPU",
    })
    @DisplayName("'rx' no se come Merx, Arx, HyperX ni Drx")
    void rxNoContaminaMerxNiHyperx(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // "set "/"kit "/"pack " se comían Sunset, Mindset, Windkit, Doypack
        "'Hoodie Sunset Circles Black',                         Buzo",
        "'Remera Mindset',                                      Remera",
        "'(CAMPERA) WINDKIT LINE FULL BLACK',                   Campera",
        "'(PANTALÓN) WINDKIT GREEN',                            Pantalón",
        "'BODY ADVANCE Creatina Doypack 300g',                  Creatina",
        "'Zapatillas adidas Dropset 4 De Mujer',                Sneaker",
        // ...y el combo de verdad sigue siendo Conjunto
        "'Conjunto Deportivo Remera y Short',                   Conjunto",
        "'Kit x2 Remeras Basicas',                              Conjunto",
        "'Pack x3 Medias Deportivas',                           Conjunto",
    })
    @DisplayName("'set', 'kit' y 'pack' no se comen Sunset, Windkit ni Doypack")
    void setKitYPackNoContaminan(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }
}
