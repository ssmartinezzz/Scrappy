package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plataforma como subcategoría de las categorías de hardware (picks-tecnologia-subcategorias).
 * Los nombres salieron del catálogo real. La migración V103 repite estas reglas en SQL sobre lo ya
 * guardado (renormalizar las reescribe desde el resolver): un cambio acá exige el mismo cambio allá.
 */
@Epic("Normalization")
@Feature("Subcategory classification")
@DisplayName("SubcategoryResolver — plataforma del hardware")
class SubcategoryResolverTecnologiaTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    @ParameterizedTest(name = "[{index}] [{1}] \"{0}\" -> {2}")
    @CsvSource({
        // CPU: socket explícito en cualquier forma de escribirlo...
        "'Procesador AMD Ryzen 5 5600GE 6/12 4.4GHz AM4',                       CPU, am4",
        "'Procesador Amd Ryzen 5 8600g (am5) C/cooler',                         CPU, am5",
        "'Procesador Intel Core i5 14600KF 5.3GHz Turbo Socket 1700 Raptor Lake', CPU, lga1700",
        "'Micro Intel I5-14400F 4.7Ghz 20Mb S.1700',                            CPU, lga1700",
        "'Procesador Intel Core Ultra 7 265K 20/20 5.5GHz LGA1851 (No incluye cooler)', CPU, lga1851",
        // ...y la generación del modelo cuando el nombre no trae socket
        "'Procesador AMD Ryzen 5 9600X',                                        CPU, am5",
        "'Procesador AMD Ryzen 7 5700X',                                        CPU, am4",
        "'Procesador Intel Core i7 14700K',                                     CPU, lga1700",
        "'Procesador Intel Core Ultra 5 245KF',                                 CPU, lga1851",
        // LGA1200 / LGA1151: por socket, por generación y por el número del Pentium / Celeron
        "'Procesador Intel Core i5 10400F LGA1200',                             CPU, lga1200",
        "'MICRO INTEL CORE I3 10105F S/VIDEO C/COOLER BOX S1200 *SIMIL 10100F*', CPU, lga1200",
        "'Micro Intel Celeron G5925 3.56 Ghz 4Mb S.1200',                       CPU, lga1200",
        "'Procesador Intel Core i5 8400 Coffee Lake',                           CPU, lga1151",
        "'Procesador Intel Celeron G4900 3.10GHz Socket 1151 OEM',              CPU, lga1151",
        "'Procesador Intel Pentium Gold G5400 3.7GHz',                          CPU, lga1151",
        "'Procesador Intel Pentium Gold G6400',                                 CPU, lga1200",
        "'MICRO INTEL PENTIUM G7400 S1700',                                     CPU, lga1700",
        "'Procesador Intel Pentium G7400',                                      CPU, lga1700",
        "'Micro Intel Core Ultra 5 245K S1851',                               CPU, lga1851",
        // un socket fuera de la taxonomía no se inventa
        "'Procesador Intel Xeon E5-2699 V3 Oem',                                CPU, ''",
        "'Procesador AMD FX 8350 AM3+',                                         CPU, ''",
        // Motherboard: socket explícito, o el chipset
        "'Mother Asrock B860M-H2 LGA1851 DDR5',                                 Motherboard, lga1851",
        "'Motherboard ASUS ROG Strix B650e-e Gaming WiFi AM5 DDR5',             Motherboard, am5",
        "'Mother MSI PRO Z790-A MAX WIFI DDR5 S1700  BULK - OUTLET',            Motherboard, lga1700",
        "'Motherboard Gigabyte B550M-K DDR4',                                   Motherboard, am4",
        "'Motherboard Gigabyte H610M K V2 DDR4',                                Motherboard, lga1700",
        "'Motherboard ASUS Prime Z890-P WiFi',                                  Motherboard, lga1851",
        "'Motherboard Biostar H610MHP DDR4 1700',                               Motherboard, lga1700",
        "'Mother Machinist X99k9 D4 Lga 2011',                                  Motherboard, ''",
        "'Motherboard ASUS Prime B460M-A',                                      Motherboard, lga1200",
        "'Motherboard MSI Z590-A PRO',                                          Motherboard, lga1200",
        "'Motherboard Gigabyte B365M DS3H',                                     Motherboard, lga1151",
        "'Motherboard ASUS Prime H110M-K',                                      Motherboard, lga1151",
        "'Motherboard Gigabyte Z270X Gaming',                                   Motherboard, lga1151",
        "'Mother Asus Prime H410M-E S.1200',                                    Motherboard, lga1200",
        "'Mother Asus Prime H410M-E Socket 1200',                               Motherboard, lga1200",
        "'Mother MSI PRO H610M-G S1700',                                        Motherboard, lga1700",
        // RAM: SODIMM antes que la generación
        "'Memoria Adata DDR5 32GB 5600MHz SODIMM',                              RAM, notebook",
        "'Memoria RAM Notebook SODIMM ADATA DDR5 8GB 5600MHz CL46',             RAM, notebook",
        "'Memoria Ram Adata XPG Lancer Blade Black 16GB 5600 Mhz DDR5',         RAM, ddr5",
        "'Memoria G.Skill DDR4 32GB (2x16GB) 3600MHz RIPJAWS V CL18 XMP 2.0',   RAM, ddr4",
        "'Memoria Ddr3 Hiksemi 4gb 1600 Mhz Hiker',                             RAM, ddr3",
        "'Memoria RAM Kimota DDR2 2GB 800MHz',                                  RAM, ''",
        "'Memoria Ram Corsair 8gb Dddr4 3200mhz Vengeance Lpx Black',           RAM, ddr4",
        // sin token DDR, la velocidad dice la generación: <= 1866 ddr3, 2133-3600 ddr4, >= 4800 ddr5
        "'Memoria RAM Corsair Vengeance 32GB(2x16GB) 6000MHz CL38',             RAM, ddr5",
        "'Memoria Ram Corsair 64Gb 6400Mhz Vengeance RGB 2x32',                 RAM, ddr5",
        "'Memoria RAM Kingston 8GB 3200 MT/s',                                  RAM, ddr4",
        "'Memoria RAM Generica 4GB 1333MHz',                                    RAM, ddr3",
        "'Memoria RAM Generica 8GB 4000MHz',                                    RAM, ''",
        "'Memoria RAM Generica 8GB',                                            RAM, ''",
        // GPU: el fabricante del chip
        "'Placa de Video PowerColor AMD Radeon RX 9060 XT',                     GPU, amd",
        "'Placa de Video Zotac GT 730 2GB DDR3',                                GPU, nvidia",
        "'Placa de Video Gigabyte NVIDIA GeForce RTX 5080 16GB AORUS MASTER',   GPU, nvidia",
        "'Placa de Video PNY RTX PRO 2000 Blackwell 16GB GDDR7 ECC IA',         GPU, nvidia",
        "'PLACA DE VIDEO RADEON RX 580 8GB ARKTEK DUAL FAN',                    GPU, amd",
        "'Placa de Video ASRock Intel Arc B580 12GB Challenger OC',             GPU, intel",
        "'Placa de Video ASRock Intel ARC Pro B60 24GB Creator IA',             GPU, intel",
        "'Placa de Video Arktek R5 230 Cyclops 2GB GDDR3',                      GPU, amd",
        "'Placa de Video Peladn Nvidia 1030GT 4Gb Ddr4',                        GPU, nvidia",
        "'Placa de Video Zotac GT1030 2GB',                                     GPU, nvidia",
        // lo que sólo parece GPU no lo es: un cooler RX, un manga con "Arc"
        "'Water Cooler Corsair Titan 240 RX LCD',                               GPU, ''",
        "'MADOKA MAGICA WRAITH ARC (PACK)',                                     GPU, ''",
        // Auricular: inalámbrico gana a gamer, y gamer a in-ear
        "'AURICULAR GAMER WIRELESS RAZER BARRACUDA X CHROMA WHITE',             Auricular, inalámbrico",
        "'Auricular c/mic Inalambrico Redragon Ire Pro White/Grey H848G',       Auricular, inalámbrico",
        "'Auriculares Gamer Fantech Portal Hq55 Rgb Black 50mm 3.5mm',          Auricular, gamer",
        "'AURICULAR JBL QUANTUM 100M2 GAMING SURROUND NEGRO',                   Auricular, gamer",
        "'Auriculares Skullcandy Dime 3 Earbuds Bone',                          Auricular, in-ear",
        "'Auricular In-Ear Philips con cable',                                  Auricular, in-ear",
        // en-oreja primero: son inalámbricos, pero son buds
        "'Auriculares Soundcore Liberty 4 True Wireless Negro',                 Auricular, in-ear",
        "'Auriculares Skullcandy Smokin Buds Matcha',                           Auricular, in-ear",
        "'Auriculares Xiaomi Redmi Buds 5 Bluetooth',                           Auricular, in-ear",
        "'Auriculares JBL Wave Beam 2 True White Bluetooh',                     Auricular, inalámbrico",
        "'AURICULAR CORSAIR VIRTUOSO MAX WIRELES SILVER',                       Auricular, inalámbrico",
        // gamer por línea o marca de gaming
        "'Auriculares Corsair HS65 Surround White',                             Auricular, gamer",
        "'Auriculares Corsair HS35 V2 Blue',                                    Auricular, gamer",
        "'Auriculares JBL Quantum 120 Black',                                   Auricular, gamer",
        "'Auricular HyperX Cloud Stinger 2 Black',                              Auricular, gamer",
        "'Auriculares Redragon Zeus Lite Wired',                                Auricular, gamer",
        "'Auriculares Logitech G335 White PC/XBOX/PS',                          Auricular, gamer",
        "'Auriculares Logitech Astro A10 2nd Gen Black',                        Auricular, gamer",
        "'Auriculares Razer Kraken V4 USB Black',                               Auricular, gamer",
        "'Auriculares SteelSeries Arctis Nova 1 Wired',                         Auricular, gamer",
        "'Auricular Fantech MH90 Sonata Black',                                 Auricular, gamer",
        "'Auriculares Asus TUF H1 A302 Gen II 7.1 Virtual',                     Auricular, gamer",
        "'Auricular Cooler Master CH351 Headset',                               Auricular, gamer",
        "'Auricular Genius GX HS-GX580U Black USB',                             Auricular, gamer",
        "'Auricular Genius HS-G600V C/ Vibración',                              Auricular, gamer",
        "'Auricular Trust GXT 433K Pylo Black',                                 Auricular, gamer",
        "'Switch Wired Chat Headset LVL1 Afterglow Black PDP',                  Auricular, gamer",
        // oficina: Logitech H###, Zone, Genius HS-### (sin la G)
        "'Auriculares Logitech H390 con mic',                                   Auricular, oficina",
        "'Auriculares Logitech Zone Vibe 100 Rosa',                             Auricular, oficina",
        "'Auricular Genius HS-02B 3.5mm Single Plug',                           Auricular, oficina",
        "'AURICULAR C/MIC GENIUS HS-04SU',                                      Auricular, oficina",
        // un "con micrófono" suelto no alcanza
        "'Auricular c/mic Generico',                                            Auricular, ''",
        // PC: gamer si lleva GPU dedicada, oficina si no
        "'PC Armada AMD Ryzen 5 5500 RTX 3050',                                 PC, gamer",
        "'PC GAMER AMD RYZEN 5 5600XT+B550+16GB+1TB M.2 NVME+RADEON RX7600 8GB', PC, gamer",
        "'PC Gamer Ryzen 5 9600x Rtx5060 Ssd 1tb 16gb B840 750w',               PC, gamer",
        "'PC Gamer Intel Core i3 12100 16GB 1TB NVMe Arc A380',                 PC, gamer",
        "'PC Armada AMD Ryzen 7 5700G+A520+16GB',                               PC, oficina",
        "'PC AMD Ryzen 5 8600G 32GB 480GB',                                     PC, oficina",
        "'PC ARMADA AMD RYZEN 5 5600GT+A520+16GB+SSD 240GB+GABINETE KIT',       PC, oficina",
    })
    @DisplayName("El hardware resuelve su plataforma")
    void hardwareResuelvePlataforma(String nombre, String categoria, String esperada) {
        assertThat(resolver.resolver(nombre, categoria)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("Un Gabinete no tiene subcategoría, ni mencionando un socket o una GPU")
    void gabineteQuedaVacio() {
        assertThat(resolver.resolver("Gabinete Gamer Kit AM5 RTX Ready Wireless RGB", "Gabinete")).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] [{1}] \"{0}\"")
    @CsvSource({
        "'Procesador Pentium Gold Cycling Edition',        CPU",
        "'Placa de Video Tenis Edition',                    GPU",
        "'Mouse Pad Running Hockey Padel',                 Mousepad",
        "'Consola Futbol Edition 500 Juegos',              Consola",
    })
    @DisplayName("Una categoría de tecnología nunca recibe un deporte")
    void tecnologiaNuncaRecibeUnDeporte(String nombre, String categoria) {
        assertThat(resolver.resolver(nombre, categoria)).isEmpty();
    }

    @Test
    @DisplayName("Un videojuego recibe su plataforma, no el deporte que nombra")
    void videojuegoRecibeLaPlataformaYNoElDeporte() {
        assertThat(resolver.resolver("Mario Tennis Aces Nintendo Switch", "Videojuego")).isEqualTo("switch");
    }

    @Test
    @DisplayName("La guarda de deporte no toca a la indumentaria")
    void indumentariaSigueConSuDeporte() {
        assertThat(resolver.resolver("Remera Tenis Club", "Remera")).isEqualTo("tenis");
    }
}
