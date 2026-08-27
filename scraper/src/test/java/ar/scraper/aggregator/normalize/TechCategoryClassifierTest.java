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
 * `richer-category-taxonomy` — las quince categorías nuevas, y el ORDEN que las
 * hace funcionar.
 *
 * <p>{@code Otros} tenía 2.974 de las 16.830 filas activas — 14% del catálogo.
 * Adentro había 453 teclados, 302 mouses, 285 fuentes, 231 discos y 161
 * productos de red. No estaban mal clasificados: ningún keyword los nombraba.
 * {@code KW_TECLADO} no tenía la palabra "teclado" pelada, sólo "teclado
 * gamer"/"teclado mecanico", así que un "Teclado Logitech K120 USB" no
 * matcheaba nada.</p>
 *
 * <p><b>Todos los nombres de abajo salieron de la base.</b> Los casos de
 * {@link #elOrdenDelBloqueTechEsLoadBearing()} son los que importan de verdad:
 * cada uno es un producto que contiene DOS sustantivos de categoría y donde
 * sólo uno describe lo que el producto ES.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("tech and sports-equipment categories")
@DisplayName("CategoryClassifier — categorías de tecnología y equipamiento deportivo")
class TechCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // El sustantivo pelado que faltaba — 453 y 302 filas en Otros
        "'Teclado Logitech K120 USB Español Negro',                    Teclado",
        "'TECLADO BLUETOOTH LOGITECH K250 GRAFITO',                    Teclado",
        "'Mouse Logitech M90 Black USB',                               Mouse",
        "'Mouse Philips M234 Usb 1000dpi Black',                       Mouse",
        // Fuente / Motherboard / Almacenamiento
        "'Fuente 550w LNZ XT550-SS - ATX',                             Fuente",
        "'Fuente Jalatec Jt-520',                                      Fuente",
        "'Motherboard Asus Prime B550M-K AM4',                         Motherboard",
        "'Disco Solido SSD 240GB Kingston A400 SATA III',              Almacenamiento",
        "'HD SSD 120GB HIKSEMI WAVE SATA III 2.5\"',                   Almacenamiento",
        "'Pendrive 64Gb Kingston DT70 Usb Tipo C',                     Almacenamiento",
        // Red / Cable / Impresión
        "'Router Wireless Mercusys 300mbps N 3 Antenas',               Red",
        "'Placa De Red Wifi Usb Archer T2u Ac600 Dual Band',           Red",
        "'Switch Tenda SG108M 8 Puertos Gigabit',                      Red",
        "'Cable HDMI-HDMI M-M Comun 1.5M',                             Cable",
        "'Adaptador Display Port M a HDMI H',                          Cable",
        "'Cable de Red RJ-45 2M',                                      Cable",
        "'Impresora Laser Pantum BP2300W Wifi',                        Impresión",
        "'Toner Alternativo Brother TN1060',                           Impresión",
        // El resto
        "'Mouse Pad Fantech MP64 Basic 640x210x2mm Black',             Mousepad",
        "'Joystick Redragon Saturn Pc USB G807',                       Joystick",
        "'Volante Logitech G29 PS5 PC Con Pedalera',                   Joystick",
        "'Microfono Fantech MCX03 Leviosa Max RGB Black USB Type C',   Micrófono",
        "'UPS Hunnox 650VA',                                           UPS",
        "'Camara Ip Cloud Tp-Link Tapo C201 Black',                    Cámara",
        "'Reloj Smartwatch Xiaomi MI Smart Band 9 Active Black',       Reloj",
        // Equipamiento deportivo
        "'Pelota De Voley DRB Classic 1.0 N°5',                        Pelota",
        "'Pelota adidas Club Uefa Champions League 26/27 Unisex',      Pelota",
        "'Paleta De Pádel Babolat Counter Vertuo 2.6',                 Paleta",
        "'Paleta De Ping Pong Rebook 5 Star',                          Paleta",
    })
    @DisplayName("Los sustantivos que vivían en Otros ahora tienen categoría")
    void losSustantivosQueVivianEnOtrosAhoraTienenCategoria(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("El orden del bloque tech es load-bearing: el contenedor gana sobre lo que contiene")
    void elOrdenDelBloqueTechEsLoadBearing() {
        // Gabinete ANTES que Fuente: 23 gabinetes activos vienen con fuente incluida
        assertThat(cat("Gabinete Gamer Kit c/Fuente 500W")).isEqualTo("Conjunto"); // "kit " gana, ver ADR-4
        assertThat(cat("Gabinete Sentey c/Fuente 500W ATX")).isEqualTo("Gabinete");

        // Gabinete ANTES que Cooler: 268 gabinetes nombran sus fans
        assertThat(cat("GABINETE COOLERMASTER ELITE 301 BLACK 3FAN ARGB")).isEqualTo("Gabinete");
        assertThat(cat("Gabinete Cooler Master Elite 302 C/Coolers x3 White")).isEqualTo("Gabinete");

        // Fuente ANTES que Cooler: 27 fuentes nombran el suyo
        assertThat(cat("Fuente Magnum Tech 600W Cooler 120mm MT-PSU600")).isEqualTo("Fuente");
        assertThat(cat("Fuente Cooler Master 650W 80 Plus Gold MWE V3 ATX 3.1")).isEqualTo("Fuente");

        // Cámara ANTES que Monitor: el producto termina en la palabra monitor
        assertThat(cat("Camara Wifi Ezviz BM1 2mp Baby Call Monitor")).isEqualTo("Cámara");

        // Mousepad ANTES que Mouse
        assertThat(cat("Mouse Pad Venex")).isEqualTo("Mousepad");

        // Notebook ANTES que RAM y Almacenamiento: la notebook los declara
        assertThat(cat("Notebook Lenovo IdeaPad 3 8GB RAM 512GB SSD")).isEqualTo("Notebook");
    }

    @Test
    @DisplayName("Un switch de teclado mecánico no es un switch de red")
    void switchDeTecladoNoEsSwitchDeRed() {
        // "Red" es un COLOR acá, y "Switch" el tipo de switch mecánico.
        assertThat(cat("Teclado Mecánico Raptor Fireclaw M87 Red Red Switch Español Blanco"))
                .isEqualTo("Teclado");
        assertThat(cat("TECLADO MECANICO RAPTOR FIRECLAW M87 RETROILUMINADO SWITCH RED OUTEMU"))
                .isEqualTo("Teclado");
        // ...y ni siquiera una remera
        assertThat(cat("Remera Boxy ZX Switch Crudo")).isEqualTo("Remera");
        // El switch de red de verdad, que nombra puertos o velocidad, sí entra
        assertThat(cat("Switch 5p Tp-Link TL-SG1005D Gigabit 10/100/1000")).isEqualTo("Red");
    }

    @Test
    @DisplayName("El color rojo ya no deja un mouse sin clasificar")
    void elColorRojoNoDejaUnMouseSinClasificar() {
        // NonTextileGuard tenía "red " (de red deportiva) y mira los primeros 35
        // caracteres: un mouse rojo entraba entero en esa ventana y salía sin
        // categoría. En el catálogo no hay una sola red deportiva.
        assertThat(cat("Mouse Logitech M110 Silent Red")).isEqualTo("Mouse");
        assertThat(cat("Mouse Logitech M280 Wireless Red")).isEqualTo("Mouse");
    }

    @Test
    @DisplayName("'Fan' quiere decir hincha en el catálogo de indumentaria")
    void fanQuiereDecirHinchaEnIndumentaria() {
        // Por esto " fan " pelado NO está en KW_COOLER.
        assertThat(cat("Remera Fiume Sport Linea Fan Godoy Cruz")).isEqualTo("Remera");
        assertThat(cat("Short Le Coq Sportif Pumas Titular Fan 2025 De Hombre")).isEqualTo("Short");
    }

    @Test
    @DisplayName("Un ratón de Disney no es un periférico, y un mousse no es un mouse")
    void unRatonDeDisneyNoEsUnPeriferico() {
        // El bloque TECH corre antes que el de ropa: sin veto, estos tres
        // entraban al catálogo como mouse. Son productos reales.
        assertThat(cat("Zapatillas Footy Mickey Mouse")).isEqualTo("Zapatilla");
        assertThat(cat("Mochila Adidas Disney Minnie Mouse")).isEqualTo("Mochila");
        assertThat(cat("DULKRE SPORT GAINER WHEY PROTEIN MOUSE DE CHOCOLATE 1.5KG"))
                .isEqualTo("Proteína");
        // ...y el periférico de verdad sigue siendo Mouse
        assertThat(cat("Mouse Logitech M90 Black USB")).isEqualTo("Mouse");
    }

    @Test
    @DisplayName("'Core I' no siempre es un Core i5")
    void coreINoSiempreEsUnProcesador() {
        // "core i" abierto se comía "Cloud Stinger Core Inalámbrico" (un
        // auricular) y "Master Liquid 360 Core II" (un water cooler).
        assertThat(cat("Auricular HyperX Cloud Stinger Core Inalámbrico White PC - PS4 - PS5"))
                .isEqualTo("Auricular");
        assertThat(cat("Procesador Intel Core i5 12400F")).isEqualTo("CPU");
    }

    @Test
    @DisplayName("Un cable se reconoce por sustantivo LÍDER, no por aparición")
    void elCableSeReconocePorSustantivoLider() {
        // Una fuente que publicita el largo de sus cables sigue siendo una fuente
        assertThat(cat("Fuente Segotep 500W ATX Cables Largos 23a Cooler 120mm Negro"))
                .isEqualTo("Fuente");
        // ...y el cable que arranca diciendo que lo es, es un cable
        assertThat(cat("Cable Splitter PWM Mallado para Fan Cooler Zer01 Gaming 1 x 4"))
                .isEqualTo("Cable");
    }

    @Test
    @DisplayName("'Patinaje Dc Shoes' son zapatillas de skate, no patines")
    void patinajeDcShoesSonZapatillasDeSkate() {
        assertThat(cat("Patinaje Dc Shoes Slathletic Heritage Hombre Blancas ZXUK-9458"))
                .isEqualTo("Zapatilla Skate");
        // ...pero el mismo prefijo en una gorra o un pantalón NO los vuelve calzado:
        // KW_SKATE_GENERICO exige esZapatilla, y el fallback de calzado corre último.
        assertThat(cat("Patinaje Dc Shoes University Cap Snapback Hombre Azules")).isEqualTo("Gorra");
        assertThat(cat("Patinaje Dc Shoes Label Beanie Hombre Beige")).isEqualTo("Gorro");
        assertThat(cat("Patinaje Dc Shoes Worker Baggy Carpenter Ril Vaqueros Hombre Indigo"))
                .isEqualTo("Baggy");
    }
}
