package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names are real rows scraped on 2026-11-20 from Macstation and Gentech (Odoo), plus the rows on other sites that the
 * same keywords could have swallowed (the A/B over the active catalogue found them; each one is pinned here).
 */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Macstation and Gentech taxonomy: Apple devices, Accesorio Apple and supplement names without the noun")
class OdooSitesTaxonomyTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        iPad Air 11" (M4) Wi-Fi                                                     | Tablet
        iPad mini Wi-Fi + Cellular                                                  | Tablet
        AirPods Pro 3                                                               | Auricular
        EarPods con conector Lightning de Apple                                     | Auricular
        Apple Watch Series 11 (GPS) 46 mm - Caja de aluminio plata                  | Reloj
        iMac 24" (M4) - 10 CPU, 10 GPU, 24GB, 512GB - Rosa                           | PC
        Mac mini (M4 Pro) - 12 CPU, 16 GPU, 24GB, 512GB                             | Mini PC
        Studio Display - Vidrio estándar - 2026                                     | Monitor
        Apple TV 4K WiFi + Ethernet 128 GB (2022)                                   | TV y Audio
        Magic Trackpad - Superficie Multi-Touch                                     | Mouse
        JBL Clip 5 - Violeta                                                        | TV y Audio
        Beats Solo Pro                                                              | Auricular
        Griffin Survivor Funda Transparente iPhone 12 Pro Max - Transparente        | Celular
        Speck Presidio Pro iPhone XS Max - Gris                                     | Celular
        Funda Speck Presidio2 Pro para 15 Pro Max                                   | Celular
        ItSkins Hybrid R Nylon Funda con MagSafe para 15 Pro Max - Negro            | Celular
        Apple EarPods - USB-C                                                       | Auricular
        Cable de carga magnético CASA C100 de USB-C a USB-C de 100 W (1 m)          | Cable
        Cable de carga magnético 4-en-1 PeAk MS401 de 60 W de Adam Elements         | Cable
        Cable de carga USB-C a MagSafe 3 (2 m) de Apple                             | Cable
        Auriculares circumaurales inalámbricos Monitor III de Marshall - Negro      | Auricular
        MacBook Air 13" (M5) - 10 CPU, 10 GPU, 16GB, 1TB - Plata                    | Notebook
        Magic Keyboard de Apple para iPad Air 11” (M4) - Español - Negro            | Teclado
        Funda VersaVu de Targus con teclado Bluetooth para iPad 11" (A16)           | Teclado
        """)
    void appleEcosystem(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada.trim());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        Smart Folio de Apple para iPad Air 11" (M4) - Azul denim                    | iPad
        Funda SafePort Rugged Max de Targus para iPad Pro 11" (M4)                  | iPad
        Protector de pantalla Pure 3 de BodyGuardz para iPad Air 13" (M4)           | iPad
        Apple Pencil Pro                                                            | iPad
        Kanex Vidrio Templado iPad Mini 5/4                                         | iPad
        Speck Balance Folio Microban iPad Mini 5/4 - Negro                          | iPad
        STM (Exclusivo Apple) Dux Plus iPad mini 7/6 - Negro                        | iPad
        Puntas para Apple Pencil de Apple - 4 unidades                              | iPad
        Funda de Apple para Apple Pencil - Negro                                    | iPad
        Funda para Tablet IPad 10,2 Gadnic COVER8 Cover Protector con Tapa          | iPad
        Funda Carry Zip de Incase para MacBook 13" - Gris cemento                   | MacBook
        Filtro de privacidad magnético para MacBook Air 15" de HyperShield          | MacBook
        Soporte plegable 6-en-1 CASA Hub Stand de USB-C a HDMI para Mac de Adam     | MacBook
        Funda Hybrid con MagSafe para AirPods 4 - Verde oliva                       | AirPods
        Funda Woolenex de Incase para AirPods Pro 2 - Grafito                       | AirPods
        Correa deportiva azul denim de Apple para caja de 42 mm - S/M               | Apple Watch
        Banda de cuero de cactus verde de Otterbox para caja de 45 mm               | Apple Watch
        Correa Trail verde/gris de Apple para caja de 49 mm                         | Apple Watch
        Funda Exo Edge de Speck para caja de 44 mm - Negro                          | Apple Watch
        Protector de Pantalla BodyGuardz para Apple Watch 41 mm Series 7/8/9        | Apple Watch
        Malla Funda De Plastico Tpu Para Apple Watch 42 44 Mm Negra                 | Apple Watch
        AirTag 2 (1 unidad)                                                         |
        UAG Scout Funda para AirTag - Orange                                        |
        """)
    @DisplayName("what is sold for an iPad, a Mac, AirPods, an AirTag or an Apple Watch is Accesorio Apple, with the device as sub")
    void accessoriesForAppleDevices(String nombre, String sub) {
        String n = nombre.trim();
        assertThat(cat(n)).isEqualTo("Accesorio Apple");
        assertThat(new SubcategoryResolver().resolver(n, "Accesorio Apple")).isEqualTo(sub == null ? "" : sub.trim());
    }

    @ParameterizedTest(name = "[{index}] {0} -> not Accesorio Apple")
    @CsvSource(delimiter = '|', textBlock = """
        Funda Presidio 2 Pro con MagSafe de Speck para iPhone 16 Pro - Negro
        Billetera de FineWoven con MagSafe para iPhone - Rojo mora
        Belkin Portable Charger, Power Bank 20000 mAh - iPhone 17, AirPods, iPad
        Soporte Tablet iPad Celular Giratorio 360 Grados Aluminio
        Funda Universal para Notebook 15.6 Pulgadas
        Soporte Para Tablet 10 Pulgadas Ajustable
        Llavero de FineWoven de Apple para AirTag - Azul marino
        Magic Keyboard de Apple para iPad Air 11” (M4) - Español - Negro
        Apple Watch Series 11 (GPS) 46 mm - Caja de aluminio plata - Correa deportiva
        iPad Air 11" (M4) Wi-Fi
        MacBook Air 13" (M5) - 10 CPU, 10 GPU, 16GB, 1TB - Plata
        """)
    @DisplayName("an iPhone accessory, a generic tablet or notebook accessory and the devices themselves are not Accesorio Apple")
    void notAppleAccessories(String nombre) {
        assertThat(cat(nombre.trim())).isNotEqualTo("Accesorio Apple");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        Whey Protein AFA 1kg                                   | Proteína
        Snack Bar                                              | Barra Proteica
        GENTECH Iron Bar 12 Unidades                           | Barra Proteica
        Iron Gel Turbo AFA                                     | Suplemento
        GU Energy Gel 32g                                      | Suplemento
        Zma Recovery                                           | Suplemento
        Iron Full Recovery                                     | Suplemento
        Amino 9000 Gentech                                     | BCAA
        Maxi Gain 750 grs                                      | Gainer
        Ripped Max II                                          | Quemadores
        Tx3 Black Cuts                                         | Quemadores
        Óxido Nítrico Gentech                                  | Pre-Workout
        Keto Crackers Pizza Afa                                | Alimentos
        Veggie Bar                                             | Barra Proteica
        """)
    void supplements(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada.trim());
    }

    @ParameterizedTest(name = "[{index}] {0} -> not {1}")
    @CsvSource(delimiter = '|', textBlock = """
        Bomba Manual de Prueba de Presión 25 Bar + Manguera Latyn      | Barra Proteica
        BANQUITO BAR SPOT 42X42X66 Cm NEGRO                            | Barra Proteica
        Spar Bar De Pie Force By Gadnic Altura Regulable               | Barra Proteica
        Barra Sensora Wii inalámbrica - Wireless Sensor Bar for Wii    | Barra Proteica
        BULL BAR Whey Protein Power Cake 1kg                           | Barra Proteica
        Nike Recovery                                                  | Suplemento
        Chinelas Hoka Ora Recovery 3 Unisex                            | Suplemento
        Masajeador Térmico Gadnic Recovery Infrarrojo 3 En 1           | Suplemento
        Combo Bsn Syntha-6 5lbs Chocolate Milkshake +  Amino Energy ON | BCAA
        Cargador Samsung 25W USB tipo C Negro con cable                | Cable
        Dehuka Cable Cargador De Carga Usb Para iPhone                 | Celular
        Velador con Parlante Bluetooth Luz Cálida Blanco Baco Macroled | TV y Audio
        Combo Auriculares + Parlante Lynx 3 en 1 Htb                   | TV y Audio
        """)
    @DisplayName("collateral found by the whole-catalogue A/B: the same words on other products change nothing")
    void collateralStaysPut(String nombre, String noEs) {
        assertThat(cat(nombre.trim())).isNotEqualTo(noEs.trim());
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        AURICULAR BEHRINGER BH 770 MONITOR DE REFERENCIA CERRADOS ESTUDIO    | Auricular
        Cable Magnetico Kolke Usb-C A Usb-C 60W 1M Kcc-8746 Gris             | Cable
        Mac Mini -Apple - M4 - 16GB - 512GB                                  | Mini PC
        Optimum Nutrition Superior Amino 2222 - 160 Tabletas                 | BCAA
        """)
    @DisplayName("rows on other stores that the new rules move on purpose (or leave alone)")
    void otherStores(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada.trim());
    }
}
