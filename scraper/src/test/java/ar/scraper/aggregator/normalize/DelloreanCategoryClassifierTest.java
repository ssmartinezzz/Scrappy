package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every name below is a real title from dellorean.com.ar, except the "must not move" controls,
 * which are plausible titles from other sites that share a keyword with it.
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("Dellorean: collectible statues, cameras and camera accessories")
@DisplayName("CategoryClassifier — Dellorean catalog")
class DelloreanCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Figura Coleccionable")
    @ValueSource(strings = {
        "Iron Studios Estatua Alien Big Chap Alien 1979 1/10 Art Scale",
        "Iron Studios Estatua Aragorn Unleashed Lord of the Rings 1/10",
        "Iron Studios Estatua T-Rex Chase Jurassic Park 1/20 Demi Art Scale",
        "Iron Studios Estatua Batman DC Trinity 1/4 Legacy Replica",
        "Iron Studios Diorama Galactus The Fantastic Four 1/20 Demi Art",
        "Iron Studios Figura Scorpion Mortal Kombat Minico",
        "Iron Studios Figura Superman & Krypto Superman Legacy DC Minico",
        "Iron Studios Figura Wolverine Unmasked Deadpool & Wolverine Minico"
    })
    @DisplayName("an Iron Studios statue, diorama or Minico figure is a Figura Coleccionable")
    void ironStudiosIsAFigure(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Figura Coleccionable");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Estatua Buda Jardin Poliresina 60cm|Decoración de Jardín",
        "Estatua Romana Grande Cemento|Decoración de Jardín",
        "Figura De Elefante|Decoración de Jardín",
        "Minicompresor 12V 10 Bar 35 L/Min Rojo y Negro Einhell|Herramienta de Auto",
        "Minicompresor de Aire 250 PSI 12V Negro Sincrolamp|Herramienta de Auto"
    })
    @DisplayName("a garden statue, a bare 'figura' or a word that merely starts with minico keeps its category")
    void gardenDecorationDoesNotMove(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Cámara")
    @ValueSource(strings = {
        "Camara De Accion Sjcam C300 4k 2800mah Bateria Wifi Mp4 128g Negro",
        "Cámara de Acción SJCAM SJ30 8K Dual Lens Estabilización SteadyMotion 2.0 Sumergible 48MP Pantalla Táctil",
        "Camara de Accion SJCAM SJ30 8K Standard Combo Estabilizada Black",
        "Camara De Accion Insta360 Ace 6k 48mp Con Detalles",
        "Camara Gopro Hero 13 Black Creator Edition 5.3k Outlet",
        "Camara Sjcam Sj10 Pro Pantalla Dual 4k 60fps Wifi 1300 Mah Negro",
        "Camara Digital SJCAM ZV100 5K Zoom 18X Flash Filtros Vlog Negro",
        "Camara Corporal SJCAM A10 Body Cam Full HD Vision Nocturna WiFi",
        "Camara para Auto SJCAM M60 1CH Dashcam 4K GPS WiFi Nocturna G-Sensor",
        "Cámara para Auto SJCAM M60S Dash Cam 4K Ultra HD WiFi Visión Nocturna Sensor G Grabación en Bucle",
        "Camara de Fotos Digital Dellorean 5K 75MP WiFi Kit de Fotografía con Trípode y Memoria 64GB",
        "Mini Camara Digital Dellorean D55 Pantalla Rebatible Llavero Retro",
        "Combo Camara De Accion Sjcam C300 4k + Memoria Card 128gb Micro SD + Cordón Magnético SJCAM",
        "Combo Camara Digital Sjcam Zv200 Video 5k Color Blanco + Memoria Card 128gb Micro SD"
    })
    @DisplayName("an action, digital, body or dash camera is a Cámara")
    void camerasAreCameras(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Cámara");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Cámara")
    @ValueSource(strings = {
        "Carcasa Sumergible SJCAM C100 C100+ Funda Acuatica 30m Original",
        "Batería Recargable SJCAM C300 Li-ion 1000mAh Repuesto Original Alta Capacidad Negro",
        "Batería Externa SJCAM SJ20 Grip Modular 1050mAh Mango Ergonómico Autonomía Extendida Negro",
        "Cargador Doble Bateria SJCAM SJ8 Cable USB Tipo C Indicador LED",
        "Tripode de Pie SJCAM Extensible Regulable Soporte Camaras 1/4",
        "Mini Tripode SJCAM Portatil Extensible Camaras Accion Vlog 1/4",
        "Baston Selfie Monopod SJCAM Extensible 92cm Aluminio Vlog 1/4",
        "Baston Selfie SJCAM Power Bank 4200mAh Bateria Empuñadura Vlog",
        "Cordón Magnético SJCAM Soporte POV Acción Compatible C200 C200 Pro Pocket Negro",
        "Colgante Magnético Para Cámara Insta360 Go 3",
        "Arnes Pecho Magnetico SJCAM S10 Soporte Pechera Pecho POV Vlog",
        "Protector De Lente Para Insta360 Go 3s Cámara De Acción",
        "Mango Control Remoto Bateria SJCAM C400 3000mAh Inalambrico Powerbank",
        "Estabilizador De Teléfono Celular Insta360 Flow Grey Gimbal",
        "Estuche Bolso SJCAM ZC2606 Funda Transporte Camara Accs",
        "Adaptador De Expansion C400 Sjcam Camara Deportiva Accesorio Negro",
        "Control Remoto Reloj SJCAM Smart Watch Bluetooth C200 C300 SJ10 SJ8",
        "Soporte Vincha Cabeza Magnetico SJCAM S10 Arnes Cabeza POV Vlog"
    })
    @DisplayName("an accessory anchored on a camera brand is a Cámara")
    void cameraAccessoriesAreCameras(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Cámara");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Memoria Card 64gb Micro SD Card - Sjcams|Almacenamiento",
        "Memoria Card Micro SD 128GB SJCAM U3 V30 98MB/s para Cámara de Acción 4K|Almacenamiento",
        "Camara de Seguridad Exterior Dellorean 3MP Super HD WiFi IP66 Reflector LED 3000LM|Cámara",
        "Webcam Dellorean Wembi 4K UHD Auto Foco Cancelacion Ruido Tripode|Webcam",
        "Funda Protectora Dellorean iPhone 17 Pro Max 3 en 1 Transparente Magsafe Pro Vidrio Templado HD y Protector de Camara|Funda",
        "Mochila Hp Para Notebook De 15.6 Modelo Urbana Color Negro/gris|Mochila",
        "Farol Linterna Dellorean LTDO1/1 Trípode Telescópico 97cm 450 Lúmenes IPX5 Power Bank Batería 10000mAh|Otros"
    })
    @DisplayName("a memory card, a security camera or a non-camera item sharing a word keeps its category")
    void camerasDoNotStealNeighbours(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Camara para Auto SJCAM M60 2CH Doble Lente Dashcam 4K GPS WiFi 1080p|Cámara",
        "Billetera Doble Dellorean Slider Automático Aluminio Protección RFID Tarjetero Inteligente Slim Hombre|Billetera",
        "Zapatillas Hoka Bondi 8 Running|Zapatilla Running",
        "Zapatillas New Balance Fresh Foam 1080v12 Running|Zapatilla Running",
        "Zapatillas Hoka 1080 Running|Zapatilla Running",
        "Ojotas Slides Nike Benassi|Ojotas",
        "Sandalia Slide Adidas|Ojotas"
    })
    @DisplayName("'1080p' is not the 1080 running shoe and 'slider' is not a slide sandal")
    void keywordsMatchWholeWords(String nombre, String esperada) {
        assertThat(cat(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not a Notebook")
    @ValueSource(strings = {
        "Cargador Pared Dellorean 140W Ultra Rápido 4 Salidas USB-C USB Macbook iPhone Notebook",
        "Cargador Pared Dellorean GaN 140W Display LCD Macbook Pro Notebook Gaming 3 Salidas USB-C USB-A",
        "Cargador Portátil Dellorean PowerOn Travel 15000mAh 65W 4 en 1 Magsafe Enchufe Rebatible Carga Notebook USB-C"
    })
    @DisplayName("a charger that mentions the laptop it powers is not a Notebook")
    void chargersAreNotNotebooks(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Notebook");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Notebook")
    @ValueSource(strings = {
        "Notebook Dell 14 DC14250 Core 5 120U 16GB 512GB 14 FHD+ Win 11 Home",
        "Notebook LG Gram Book Intel Ultra 5 8GB 512GB SSD 15.6 Touch Win11"
    })
    @DisplayName("a real notebook stays a Notebook")
    void notebooksStayNotebooks(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Notebook");
    }
}
