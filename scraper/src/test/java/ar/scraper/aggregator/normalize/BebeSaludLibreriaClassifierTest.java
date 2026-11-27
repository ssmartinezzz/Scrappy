package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V144: Artículo de Bebé, Salud and Librería, each rule with the negative next to it. Every name is a catalogue row (2026-11-26). */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Artículo de Bebé, Salud and Librería — category over real names")
class BebeSaludLibreriaClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @CsvSource(delimiter = '|', value = {
        "Cochecito Oxford Cabin Maxi-Cosi",
        "Cochecito Bebesit LUGGY Gris",
        "Coche Travel System Bebesit 1337Ts-L",
        "Coche Compacto Bebesit Foldy con Capota Extensible Negro",
        "Practicuna Corralito Gris Bebesit",
        "Cuna Colecho Tori Beyond Graphite Eco Maxi Cosi",
        "Andador Sebigus 56176C",
        "Mamaderas Natural Response Philips Avent 260ml Deco Jirafa",
        "Chupete Ultra Soft Philips Avent SCF091/05 0-6M sin BPA 2 un",
        "Babero Silicona Minikoioi Flexi Bib Ajustable Con Bolsillo Mineral Blue",
        "Pañales Pampers Baby Dry Hipoalergénico Talle G",
        "Sacaleches Eléctrico Doble My Baby Boom sin bolso",
        "Extractor de Leche Manual Philips Avent",
        "Esterilizador De Mamaderas Para Microondas",
        "Aspirador Nasal Eléctrico Bebés Recargable Música Sacamocos Color Celeste",
        "Tetinas NaturalWave Flujo Lento",
        "Parasol para cochecitos Maxi Cosi",
        "BASE PARA AUTO FAMILYFIX 360 MAXI COSI",
        "Gimnasio Piano para bebes 8705 Bebesit Arcoiris Celeste",
        "Puerta De Seguridad Para Bebés Vintage Safety 1st",
        "Reja de Seguridad Bebé 67-73x76 Cm Metal Blanco M+Design",
    })
    void babyItems(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Artículo de Bebé");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @CsvSource(delimiter = '|', value = {
        "Monitor para Bebés See Pro Maxi-Cosi",
        "Monitor De Bebe Philips Avent SCD973/37 Wifi 1080p",
        "Baby Call Monitor De Bebé Gadnic Pantalla IPS 3.5\" Cámara PTZ 720P Visión Nocturna",
        "Camara Nexxt IP Baby Monitor NHC B100 Blanco Sin Cargador",
        "Cámaras De Seguridad Baby Call x2 Gadnic 702 Full HD 1920x1080",
    })
    @DisplayName("a baby monitor or baby call beats the Monitor and Cámara families")
    void babyMonitors(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Artículo de Bebé");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Bebote Realista Soft Bruno 38cm con Pañalero y Mantita Celeste con Diseño | Otros",
        "Moises para Mascotas Cuzquitos Azul 90cm                                  | Otros",
        "Chupete para Canilla Plástico 1/2 Pulgada 2 Un Italian                    | Otros",
        "Andador Monopatin 3 en 1 Con Luces y Plegable Verde                       | Otros",
        "Auto a Batería Fiat 500 Infanti Rojo 12V                                  | Otros",
        "Pañales Para Adultos Slip Talla L                                         | Salud",
        "Monitor Gamer Samsung Odyssey G5 27 144hz                                 | Monitor",
        "Soporte Escritorio Para Monitor Obebox Ultra Resistente 17 A 40           | Monitor",
        "Sonajero Molino Con Sopapa Para Bebes                                     | Otros",
    })
    @DisplayName("what only shares a word with a baby item is not one")
    void notBabyItems(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @CsvSource(delimiter = '|', value = {
        "Termometro Digital Microlife Mt 1931 Punta Flexible",
        "Tensiómetro Aneroide Palm Std Gris Coronet",
        "Tensiómetro Digital De Brazo Gadnic Monitor de Presión Arterial Automático",
        "Oxímetro De Pulso Para Dedo Icare Standard",
        "Nebulizador Aspen a pistón BR-CN176",
        "BALANZA DE BANO VONDOM UPOWER02P DIGITAL SIN PILAS ROSA",
        "Balanza Digital Inteligente GADNIC BT Corporal 180kg Bioimpedancia",
        "Balanza Personal Aspen B15",
        "Balanza GA.MA Fit Care",
        "Masajeador Therabody Theragun Sense Black",
        "Pistola Masajeadora Gadnic Gunax Fisioterapia de Alta Intensidad",
        "Masajeador De Ojos EyeRelax 3D Gadnic Electrico Inalambrico",
        "Almohadilla Térmica Gadnic ALM2231 6 Configuraciones De Calor indicador LCD",
        "Pañales Grande Para Adultos X100 Hennia Recto Incontinencia Fuerte Recto",
        "Nonino Recto Extra Grande Pañales Descartables Para Adulto 50 Unidades",
        "Preservativos Maxx Texturados Tachas 6 Un",
        "Kit X4 Plantillas Ortopédicas Deportivas Antifascitis 35-40",
        "Corrector Postural Straight 8",
        "Apósitos Curitas Kids Aqua Protect",
        "Barbijo Tricapa Descartable 2 Un Celeste Healthy Mask",
    })
    void healthItems(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Salud");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Balanza Digital De Cocina Gadnic 5kg Precisión 1g Acero Inoxidable   | Pequeño Electrodoméstico",
        "Balanza De Precisión Miligramos Gadnic 0,001 Gramos A 50 Gramos Portatil | Otros",
        "Balanza Comercial Systel Cuora Max ST Con Mastil 30kg Ticket Digital Led | Otros",
        "Termómetro Digital para Parrilla 20,5 Cm Weber 6750                  | Otros",
        "Tobillera Elástica Ajustable Deportiva Soporte                       | Otros",
        "Pistola Masajeadora 2 Pelotas 4 Puntas ELITE PERCUSSION BULLTEC MT386 | Salud",
        "Barbijo Mascarilla Descartable Fravida Para Polvo X5 Unid            | Protección Personal",
        "Crema Dental Colgate Protección Anticaries 90 Gr                     | Otros",
    })
    @DisplayName("a kitchen, precision or commercial scale, a food thermometer, sport supports and oral care are not Salud")
    void notHealthItems(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @CsvSource(delimiter = '|', value = {
        "Cuaderno 22x29cm 84 Hojas Rayadas Essential Ledesma Amarillo",
        "Cuaderno Arte 29,7 Tapa Semirrígida 100 Cuadriculada Espiral Negro",
        "Carpeta Escolar 3x40 Comfy Bear Hologlitter Rexon Lila",
        "Carpeta Oficio 3 Solapas Argentina Ry Negro",
        "Cartuchera Footy Cool Friends Luz Led Verde",
        "Cartuchera Canopla Rectangular 1 Piso Jake Y Los Piratas",
        "Resma A4 Papel Glossy Satinado Photo Quality X 100 Hojas 135Gr NewAkira Inkjet GLOSSY135",
        "Agenda Docente 2027 15x21 Espiral Semanal Ry Rosa Pálido Huella 2027",
        "Libreta Aros Premium Diseño Exclusivo Capibara Stitch Labubu - Jugo",
        "Bibliorato Oficio Ezco Pvc Negro Lomo Ancho 7cm Con Anillos Negro",
        "Marcador Escolar Faber Castell Fiesta X10",
        "Set X36 Marcadores Doble Punta Colores Surtidos Con Estuche",
        "Calculadora Casio Cientifica Fx-82 La Plus 252 Funciones Negro",
        "Abrochadora Mit 64 Pinza Pintada Clasica Azul Petróleo",
        "Blinder Clip Sifap 41 Mm Negro 12 Unidades Metálico Negro",
        "Block Husares Espiralado Esquela / A5 Cuadriculado Pack X6u",
        "Sobre Oficio Ingles 80gr x 100 unidades",
        "Tempera Model Pote X 250 Grs X Color Color Celeste Celeste",
    })
    void stationery(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Librería");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Carpeta Semirrígida Hawai 2x1,30                                 | Otros",
        "Carpeta de Adiestramiento 15 Un Pets Fun                         | Higiene de Mascota",
        "Lápiz Labial Pearl Hipoalergénico - Pearl Bronce                 | Maquillaje",
        "Lápiz para Cortacerámica 8 mm Robust                             | Otros",
        "Lápiz 3D 3dpen 1u + Filamentos Varios Colores                    | Otros",
        "Set 10 Lápices de Carpintería con Afilador Robust                | Otros",
        "Block Night Light - Decorative Lamp Collectible - Luz de noche - Lámpara | Iluminación",
        "Papel Vinílico Blanco Geométrico 5 M2 Muresco                    | Otros",
        "Notebook Lenovo IdeaPad 15 Ryzen 5 16GB 512GB                    | Notebook",
        "Clips para Ropa 7.2x1.3x0.9 Cm Madera 24 Un M+Design             | Otros",
    })
    @DisplayName("a hardware, beauty or computer word is not stationery")
    void notStationery(String nombre, String categoria) {
        assertThat(cat(nombre)).isEqualTo(categoria);
    }

    @Test
    @DisplayName("the new families only name what no family claimed: a rattle, a baby-bed mattress and a school bag keep theirs")
    void theyDoNotStealFromOtherFamilies() {
        assertThat(cat("Colchón Espuma Cuna 65x97x8 Cm Soñador")).isEqualTo("Colchón");
        assertThat(cat("Mochila Escolar Footy 18 Pulgadas Reforzada")).isEqualTo("Mochila");
    }
}
