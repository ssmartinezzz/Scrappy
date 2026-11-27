package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** TR3: names that read as a technology category and are not, with the negative next to each rule. Every name is a catalogue row. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Residues in tecnologia — category and sub-category over real names")
class ResiduosDeTecnologiaClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    private String catConRaw(String raw, String nombre) {
        return classifier.normalizarCategoria(raw, nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Asadera Panelux 1190023                                              | Bazar",
        "Asadera/Fuente para Horno con Asas 33X18X5 Porcelana Oxford          | Bazar",
        "Fuente Pyr-O-Rey 1001403                                             | Bazar",
        "Fuente Rectangular Para Horno 1.9Lts De 26.9 X 17 Cm - Pyr O Rey      | Bazar",
        "Set X 3 Fuentes De Vidrio Para Horno                                 | Bazar",
        "Set de Fuentes Argenprom 30308                                       | Bazar",
        "Molde Para Budín 26 Cm Tramontina Brasil Aluminio Antiadherente      | Bazar",
        "Kit Moldes Silicona Budín 19cm + Tortera 20cm + Tortera 15cm         | Bazar",
        "Pizzera 31 cm Antiadherente Fuente Horno Asadera Cocina              | Bazar",
        "Pack de 2 Flaneras de silicona con bordes y asas reforzadas          | Bazar",
        "Bandeja Nouvelle Cuisine 1140810                                     | Bazar",
        "Set x 3 Bandejas Fuentes Organizadores Bambu                         | Bazar",
        "Bandeja Redonda Silicona Airfryer Freidora De Aire                   | Bazar",
        "Batea Madera y Alpaca 20 cm                                          | Bazar",
        "Set X 3 Recipientes De Vidrio Cuadrados Con Tapa                     | Bazar",
        "Molde Muffins x 6 Cupcake Antiadherente Horno Reposteria             | Bazar",
    })
    void bakewareIsBazar(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
        assertThat(catConRaw("Fuente", nombre)).as("with the stored 'Fuente' as raw").isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "FUENTE 650W RAPTOR VOLT ZU-ATX65XY 80PLUS BRONZE NEGRO                | Fuente",
        "Fuente XPG KYBER 750W 80 Plus Gold ATX 3.1 PCIe 5.0 12VHPWR           | Fuente",
        "Fuente de Poder XPG PROBE600B 600W 80 PLUS Bronze  Ventilador 120mm   | Fuente",
        "Fuente Cooler Master 850w Sfx Gold 80+ Itx                            | Fuente",
        "Batea Lavadora Ultrasónica Gadnic Digital Tanque 2.5 Lts Acero Inoxidable 220V | Otros",
        "Bandeja de Cubitos de Hielo Gadnic CB36 Hielera de Silicona Ecológica | Bazar",
        "Budinera para Masilla de Plástico 35 Cm Hunter                        | Otros",
        "Set x500 de Moldes Cápsula de Uñas Gadnic Uñas Esculpidas             | Otros",
    })
    void powerSuppliesAndLookalikesStay(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @Test
    @DisplayName("a beach mould and a toy sewing machine are still toys (the toy store's leaf says so; the new rules do not claim them)")
    void toysAreNotBakewareNorAppliances() {
        assertThat(catConRaw("Juguete", "Moldes de Playa Duravit Tiempo Libre +12m")).isEqualTo("Juguete");
        assertThat(catConRaw("Juguete", "Máquina De Coser Infantil - Realista Con Luz Y Sonido")).isEqualTo("Juguete");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Escobero Mosconi 10162                                               | Mueble",
        "Porta Microondas Delos Alfa Dl630/6Br                                | Mueble",
        "Frutero Ovalado Borde Color                                          | Bazar",
        "Deposito de jardin 194x097x200 Calibre 25 Techo 1 agua Negro DON ACERO | Depósito de Jardín",
        "Cobertizo Exterior South Port By Gadnic Acero Galvanizado            | Depósito de Jardín",
        "Gabinete Con 6 Cajones Jadever Jdcs4a08                              | Caja de Herramientas",
        "Gabinete Porta Herramienta 2 En 1 8 Cajones                          | Caja de Herramientas",
        "Carro Gabinete Metálico Jadever Con 3 Cajones Y Ruedas Verde Oscuro  | Caja de Herramientas",
        "Gabinete Gamemax Nova N5 Mid Tower ATX 1xFan RGB                     | Gabinete",
        "Gabinete XPG LANDER300 Blanco con ARGB y Vidrio Templado             | Gabinete",
        "GABINETE ATX KIT JALATEC BAREBONE JT-K709 TECLADO / MOUSE / PARLANTES / FUENTE 500W | Gabinete",
        "Escobero Cuelga Escobas Organizador Colgante Con Ganchos Gris        | Organización",
    })
    void cabinetsThatAreFurnitureAndToolsAreNotPcCases(String nombre, String categoria) {
        assertThat(catConRaw("Gabinete", nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}/{2}")
    @CsvSource(delimiter = '|', value = {
        "Zapatilla Eléctrica Alargue Prolongad Usb Universal Térmica           | Ficha y Prolongador | ",
        "Adaptador de enchufes universal                                      | Ficha y Prolongador | ",
        "Cable alargue USB 2.0 amplificado 20m NISUTA - NSCAEXUS20            | Cable               | usb",
        "CARTERA PORTA LAPTOP DE CUERO DUSSELDORF                             | Bolso               | cartera",
        "Notebook Lenovo IdeaPad 15.6 Ryzen 5 16GB                            | Notebook            | ",
        "Botas Outdoor Montagne Seilas GTX Mujer                              | Botas               | ",
        "Placa de Video Gigabyte GeForce RTX 5060 Windforce OC 8G             | GPU                 | nvidia",
        "MICRO PROCESADOR AMD ATHLON 3000G 3.5GHZ AM4 C/RADEON VEGA 3         | CPU                 | am4",
        "MOTEHRBOARD MSI A520M-A PRO DDR4 AM4                                 | Motherboard         | am4",
        "Placa Madre MSI A520M-A PRO AM4 DDR4 Micro ATX                       | Motherboard         | am4",
        "Memoria RAM Kingston Fury 16GB DDR4 3200                             | RAM                 | ddr4",
        "Griffin USB-C to Lightning Cable 6FT - White                         | Cable               | ",
        "Belkin Portable Charger, Power Bank with Integrated Cable 20000 mAh 30W USB C | Celular   | Batería Externa",
    })
    void strayRowsGoWhereTheyBelong(String nombre, String categoria, String sub) {
        String cat = catConRaw("", nombre);
        assertThat(cat).isEqualTo(categoria);
        assertThat(subs.resolver(nombre, cat)).isEqualTo(sub == null ? "" : sub.strip());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Toy Story Disney Tablet Didactica con Luces                          | Juguete",
        "Pizarra Mágica Tablet Lcd 9 Pulgadas Infantil                        | Juguete",
        "Tablet Didactica Con Actividades Con Luces Y Sonidos Ok Baby         | Juguete",
        "Tablet Noblex Toy Story 10.1'' 6/64gb Verde                          | Tablet",
        "Tablet Lenovo Tab M11 MediaTek Helio G88 4GB RAM 128GB SSD           | Tablet",
        "Tablet Infantil ICONLINK Q8K 7 Pulgadas 32GB 4GB RAM Android 12      | Tablet",
    })
    void toyTabletsAreToys(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Cargador Para Reloj Mi Band 5 6 7 Xiaomi                             | Celular",
        "CargadorPara Reloj Mibro Watch T1 T2 C2 Mibro Air Xpaw001 A2 C3       | Celular",
        "Cargador Para W12 W26 Kw66 Ls05 Sw022 Solar Ls05 Moto Watch 100 Kieslect L11 K10 | Celular",
        "Funda Para Reloj Xiaomi Redmi Watch 5 Lite Negro                     | Celular",
        "Funda Protectora De Pantalla Xiaomi Redmi Watch 5 Active Negro       | Celular",
        "Malla Para Reloj Xiaomi Mi Band 5 6 7 Azul Oscuro                    | Reloj",
        "Smartwatch Samsung Galaxy Watch 6 Classic 43mm BT                    | Reloj",
        "Funda Carcasa Protectora para Apple Watch Ultra 49mm                 | Accesorio Apple",
        "Protector Fox Socks Grip Band Unisex                                 | Medias",
        "Funda Notebook 14 Pulgadas Con Cierre Dehuka Color Negro             | Notebook",
    })
    void watchAccessories(String nombre, String categoria) {
        assertThat(catConRaw("Accesorio Apple".equals(categoria) ? "Accesorio Apple" : "", nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Malla Para Reloj Xiaomi Mi Band 5 6 7 Azul Oscuro                    | malla",
        "Pack 3 Malla Para Reloj Xiaomi Mi Band 5 6 7 Negro Azul Gris         | malla",
        "Malla Nylon Para Reloj Xiaomi Mi Smart Band 8 9 Band 10 Borde Acero Naranja | malla",
        "Correa de Titanio                                                    | malla",
        "Correas Maledan compatibles con Samsung Galaxy Watch 7 6 5 4 FE 20mm | malla",
        "Pulsera Ocean para Apple Watch Ultra Amarillo 49-45-44-42-41-40mm    | malla",
        "Pulsera Smart Xiaomi Band 10 Pro                                     | smartwatch",
        "Hello Watch 3 Plus Total Black 4gb Doble Malla                       | ",
        "RELOJ CASIO F-91W-1D                                                 | analógico",
        "Reloj Analógico Gadnic RELF1 Pulsera Malla Correa Simil Cuero Negro y Dorado | analógico",
        "Reloj de Cuarzo Gadnic 1513BK Hombres Impermeable                    | analógico",
        "Reloj Digital Gadnic S400 Negro Sumergible                           | analógico",
        "Reloj Casio Deportivo Digital Ws-1400h-8bvcf Gris Oscuro Unisex      | deportivo",
        "Reloj Smartwatch Garmin Forerunner 70 - 42 mm                        | smartwatch",
        "Reloj Garmin Forerunner 170                                          | ",
        "Reloj Digital Infantil Avengers Proyector De Imagenes Hulk           | ",
        "Reloj Comparador Profesional De 0 A 10mm Acero Cr-v Lusqtoff Digital | ",
        "Radio Reloj Havit M3                                                 | ",
        "Reloj Control Horario Gadnic C2600 Biométrico Huella Digital         | ",
        "Apple Watch SE 3                                                     | ",
    })
    void relojSubs(String nombre, String sub) {
        assertThat(subs.resolver(nombre, "Reloj")).isEqualTo(sub == null ? "" : sub.strip());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Máquina de Coser Gadnic 508A 16 Puntadas Recta Zig Zag con Pedal     | Pequeño Electrodoméstico",
        "Maquina de Coser Singer 3223                                         | Pequeño Electrodoméstico",
        "Maquina Para Coser Necchi K417a                                      | Pequeño Electrodoméstico",
        "Afeitadora Philips Qp2724/10 Oneblade Face                           | Pequeño Electrodoméstico",
        "Cortadora de Pelo Profesional Gadnic CP140 Ergonómica                | Pequeño Electrodoméstico",
        "Exprimidor Atma Ex8220n Blanco 0.8 Lts.                              | Pequeño Electrodoméstico",
        "Balanza de Baño Gadnic Digital con APP Blanca                        | Salud",
        "Balanza de Cocina Argenprom 10380                                    | Pequeño Electrodoméstico",
        "Balanza GA.MA SCF 2000                                               | Pequeño Electrodoméstico",
        "Juguera Oster Clasica 600W                                           | Pequeño Electrodoméstico",
        "Balanza De Precisión Miligramos Gadnic 0,001 Gramos A 50 Gramos Portatil | Otros",
        "Balanza Digital De Precisión 0.01g A 200g Display Joyeria                | Otros",
        "Balanza De Cocina Digital Gadnic 5Kg Precisión 1G Pantalla LCD Función Tara | Pequeño Electrodoméstico",
        "Balanza Comercial Systel Cuora Max ST Con Mastil 30kg Ticket Digital Led | Otros",
        "Turbo Ventilador Liliana Vtfm18 18\" 90w Cromado                      | Ventilador",
        "Kit x15 Pie Prensatelas Gadnic Para Maquina de Coser                 | Otros",
    })
    void appliancesThatReadAsOtros(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Base de carga magnética plegable 3-en-1 Mag 3 Pro de 15 W de Adam Elements | Celular",
        "Base de carga inteligente 3-en-1 Mag Qube Qi2 de 25 W de Adam Elements     | Celular",
        "Base de Carga Para Mouse Gamer                                             | Mouse",
    })
    void chargingBases(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }
}
