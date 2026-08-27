package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las reglas tier-1 nuevas: las quince categorías de
 * {@code richer-category-taxonomy} más ocho de indumentaria que no tenían.
 *
 * <p><b>Ninguna es un default incondicional</b> (entrada de un solo elemento,
 * como la de {@code Gorro}). Una subcategoría que no se puede leer del nombre
 * tiene que quedar VACÍA, no adivinada — mismo criterio con el que se abstiene
 * el clasificador visual: {@code ""} significa "no sé", no "genérico". Lo fija
 * {@link #loQueNoSePuedeLeerDelNombreQuedaVacio()}.</p>
 *
 * <p>Los nombres salieron del catálogo real.</p>
 */
@Epic("Normalization")
@Feature("Subcategory classification")
@Story("tier-1 rules for the new categories")
@DisplayName("SubcategoryResolver — subcategorías de las categorías nuevas")
class SubcategoryResolverTechRulesTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    @ParameterizedTest(name = "[{index}] [{1}] \"{0}\" -> {2}")
    @CsvSource({
        // Almacenamiento — nvme ANTES que ssd: un M.2 NVMe dice las dos cosas
        "'Disco Solido SSD 128GB Hiksemi Wave M.2 NVMe PCIe x4 3.0',  Almacenamiento, nvme",
        "'Disco Solido SSD 240GB Kingston A400 SATA III',             Almacenamiento, ssd",
        "'Disco Duro Externo 1Tb Seagate Portable Drive',             Almacenamiento, hdd",
        "'Pendrive 64Gb Kingston DT70 Usb Tipo C',                    Almacenamiento, pendrive",
        // Red
        "'Router Wireless Mercusys 300mbps N 3 Antenas',              Red, router",
        "'Switch Tenda SG108M 8 Puertos Gigabit',                     Red, switch",
        "'Adaptador de Red Tp-Link Archer TX1U Nano AX300 WIFI 6 USB', Red, adaptador",
        // Cable
        "'Adaptador Display Port M a HDMI H',                         Cable, video",
        "'Cable de Red RJ-45 2M',                                     Cable, red",
        "'Cable USB a Micro USB 2A 1m Strong Series Negro',           Cable, usb",
        // Impresión
        "'Impresora Laser Pantum BP2300W Wifi',                       Impresión, impresora",
        "'Toner Alternativo Brother TN1060',                          Impresión, toner",
        "'Botella Tinta Epson Alternativa T664 Cyan L800',            Impresión, tinta",
        // Cooler — líquida ANTES que aire: un AIO dice "water cooler" Y "cooler"
        "'Cooler CPU Cougar Poseidon Ultra ARGB 360 Water Cooler',    Cooler, líquida",
        "'Grasa Termica Cooler Master Cryofuze 7',                    Cooler, pasta",
        // Fuente
        "'Fuente Adata XPG 1300W 80 Plus Platinum Modular',           Fuente, modular",
        "'Fuente Cooler Master 650W 80 Plus Gold MWE V3 ATX 3.1',     Fuente, '80 plus'",
        // Periféricos
        "'Teclado Mecánico Redragon Azure Pro K652GG Red Switch RGB', Teclado, mecánico",
        "'TECLADO BLUETOOTH LOGITECH K250 GRAFITO',                   Teclado, inalámbrico",
        "'Mouse Inalambrico Logitech M190 Black',                     Mouse, inalámbrico",
        "'Mouse Redragon Cobra Black M711 RGB Gaming',                Mouse, gamer",
        "'Monitor Samsung 27 Curvo Full HD',                          Monitor, curvo",
        "'Notebook Lenovo LOQ Gamer RTX 3050',                        Notebook, gamer",
        "'Reloj Smartwatch Xiaomi MI Smart Band 9 Active Black',      Reloj, smartwatch",
        "'Volante Logitech G29 PS5 PC Con Pedalera',                  Joystick, volante",
        "'Camara IP Nexxt Exterior A Bateria Smart NHC-O630',         Cámara, exterior",
        "'Paleta De Ping Pong Rebook 5 Star',                         Paleta, 'ping pong'",
    })
    @DisplayName("Las categorías nuevas resuelven su subcategoría")
    void categoriasNuevasResuelvenSubcategoria(String nombre, String categoria, String esperada) {
        assertThat(resolver.resolver(nombre, categoria)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] [{1}] \"{0}\" -> {2}")
    @CsvSource({
        "'Remera Oversize Negra Boxy',                    Remera, oversize",
        "'Remera de Gym Compression Negra',               Remera, deportiva",
        "'Remera Manga Larga Rustica',                    Remera, 'manga larga'",
        "'Camisa Manga Larga Lino Militar',               Camisa, lino",
        "'Lumberjack Shirt Cuadros',                      Camisa, cuadros",
        "'Jean Cargo Azul de Hombre',                     Jean, cargo",
        "'Jean Mom Tiro Alto',                            Jean, mom",
        "'Pantalón de Vestir Negro',                      Pantalón, vestir",
        "'Pantalon Jogger Beige',                         Pantalón, jogger",
        "'Gorra adidas Trucker Negra',                    Gorra, trucker",
        "'Gorra Snapback New Era',                        Gorra, snapback",
        "'Vestido Largo Floreado',                        Vestido, largo",
        "'Vestido Corto de Fiesta',                       Vestido, corto",
        "'Botines Futsal adidas Predator',                Botines, futsal",
        "'Mochila Porta Notebook 15.6',                   Mochila, notebook",
    })
    @DisplayName("Ocho categorías de indumentaria que no tenían tier-1 ahora lo tienen")
    void indumentariaGanaSubcategoria(String nombre, String categoria, String esperada) {
        assertThat(resolver.resolver(nombre, categoria)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("Lo que no se puede leer del nombre queda vacío, no adivinado")
    void loQueNoSePuedeLeerDelNombreQuedaVacio() {
        // Ninguna de las reglas nuevas trae default incondicional: un nombre que
        // no dice nada sobre el subtipo devuelve "", que es el "no sé" honesto.
        assertThat(resolver.resolver("Fuente Jalatec Jt-520", "Fuente")).isEmpty();
        assertThat(resolver.resolver("Teclado Logitech K120", "Teclado")).isEmpty();
        assertThat(resolver.resolver("Motherboard MSI B840M-B PRO", "Motherboard")).isEmpty();
        assertThat(resolver.resolver("UPS Hunnox 650VA", "UPS")).isEmpty();
    }

    @Test
    @DisplayName("El tier-2 transversal sigue cubriendo lo que el tier-1 no nombra")
    void elTier2TransversalSigueCubriendo() {
        // Pelota y Paleta NO declaran tier-1 para deporte a propósito: el scan
        // transversal ya tiene futbol/voley/basquet/padel y los resuelve solo.
        assertThat(resolver.resolver("Pelota De Voley DRB Classic 1.0", "Pelota")).isEqualTo("vóley");
        assertThat(resolver.resolver("Paleta De Pádel Babolat Counter Vertuo 2.6", "Paleta")).isEqualTo("pádel");
    }
}
