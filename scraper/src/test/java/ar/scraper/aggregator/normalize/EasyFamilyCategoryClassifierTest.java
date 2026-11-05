package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category")
@Story("Easy taxonomy families")
@DisplayName("CategoryClassifier — one representative table per family added for Easy")
class EasyFamilyCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private static Arguments caso(String nombre, String esperado) {
        return Arguments.of(nombre, esperado);
    }

    private void assertCategoria(String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isEqualTo(esperado);
    }
    static Stream<Arguments> construction() {
        return Stream.of(
                caso("Cemento 25 Kg Loma Negra", "Cemento"),
                caso("Ladrillo Hueco 12X18X33 Cm Fanelli", "Ladrillo"),
                caso("Hierro Redondo Adn 10 Mm X 12 Mts Acindar", "Hierro"),
                caso("Chapa Acanalada C27 1.10X3.50 Mts Siderar", "Chapa"),
                caso("Placa Yeso 9.5 Mm X 1.20 X 2.40 Mts Maxhaus", "Placa de Yeso"),
                caso("Montante 34 DryWall Plus 260x3.4x0.5 Cm Gris Barbieri", "Perfilería"),
                caso("Látex Interior Blanco Proclassic X 20 Lts Sherwin Williams", "Pintura"),
                caso("Enduido Interior Blanco Mate 20 Lts Sinfonia", "Enduido"),
                caso("Aguarrás Diluyente 900 Ml Casablanca", "Diluyente"),
                caso("Puerta 200x70 Cm Acero y Madera Blanco Apertura Izquierda Prisma Oblak", "Puerta"),
                caso("Ventana PVC 150x110 Cm Vidrio DVH Blanco Fracas", "Ventana"),
                caso("Mosquitero Aluminio Ventana 120X110 Byggbasen", "Mosquitero"),
                caso("Cerámico para Piso 51X51 Cm Gris Johao Grey Cerámica Alberdi", "Cerámico"),
                caso("Porcelanato 60X60 Cm Gris Osiris Tiza Cerámica Alberdi", "Porcelanato"),
                caso("Pastina Gris 1 Kg Klaukol", "Pastina"),
                caso("Piso Vinílico Cerezo 5Mm 2.196 Mts SPC Baldara", "Piso"),
                caso("Moldura 4 Mts Poliestireno Expandido 50x50 Mm Estisol", "Moldura"),
                caso("Escalera Multiproposito 16 Pasos", "Escalera"),
                caso("Toldo 2.7x2.4 Mts Poliéster Verde y Blanco Baldara", "Toldo"),
                caso("Tirante Pino Cepillado 2X4X3.05 Mts Arauco", "Madera"),
                caso("Placa Osb 1.22x2.44 Mts 9.5mm Lp Argentina", "Tablero"),
                caso("Membrana Impermeabilizante Blanco Satín 20 Kg Caucho Reciclado Venier", "Impermeabilizante"),
                caso("Bolsa de Arena Fina 20 Dm3 Distribuidora Don Tino", "Árido"),
                caso("Aislante Goma Eva y Aluminio 180x50x0.6 Cm Azul y Gris Alpes", "Aislante"),
                caso("Césped Sintético Verde 40 Mm 30x30 Cm Baldara", "Césped Sintético"),
                caso("Adhesivo Cementicio Impermeable 25 Kg Gris Granidur", "Adhesivo"),
                caso("Rodillo Antigota 22 Cm Eco Mc Carthy", "Accesorio de Pintura"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("construction")
    @DisplayName("construction materials, paints, openings and floors")
    void constructionNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> installations() {
        return Stream.of(
                caso("Caño Termofusión PN20 25 Mm X 4 Mts Polipropileno Tubofusion", "Caño"),
                caso("Codo 90° 25 Mm Polipropileno Acqua System", "Accesorio de Caño"),
                caso("Llave de Paso Total 25 Mm Polipropileno Acqua System", "Llave de Paso"),
                caso("Flexible para Agua Mallado Acero Inox 1/2X50 Cm Latynflex", "Flexible"),
                caso("Tanque Tricapa Gris 600 Lts Rotoplas", "Tanque de Agua"),
                caso("Bomba Periférica 1/2 Hp 33 Mts Nero Fluvial", "Bomba de Agua"),
                caso("Canaleta PVC 3 Mts Pluvitubo", "Canaleta"),
                caso("Cable Unipolar 2.5 Mm Celeste Plastix\"Cf\" X 30 Mts Imsa", "Cable Eléctrico"),
                caso("Interruptor Termomagnético 2 Polos Curva C 16 A 4,5 kA SICA", "Térmica"),
                caso("Caja de Embutir Octogonal Liviana Galvanizada AG Metalúrgica", "Caja Eléctrica"),
                caso("Cablecanal con Adhesivo 20x10 Mm 2 Mts Roker", "Cablecanal"),
                caso("Ficha Macho Negro 20 A Jeluz", "Ficha y Prolongador"),
                caso("Interruptor 1 Módulo Blanco 10 A Monaco Macroled", "Llave de Luz"),
                caso("Luz Led Bulbo Fría E27 15 W Candela", "Iluminación"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("installations")
    @DisplayName("plumbing, gas and electrical installations")
    void installationsNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> tools() {
        return Stream.of(
                caso("Taladro Percutor Inalámbrico 20V 10 Mm + 1 Batería BCD704C1-AR Black & Decker", "Taladro"),
                caso("Amoladora Angular 115 Mm 820 W G720N Black & Decker", "Amoladora"),
                caso("Sierra Circular 184 Mm 1400 W 5300 Rpm Black & Decker", "Sierra"),
                caso("Soldadora Inverter 215A Sincrolamp", "Soldadora"),
                caso("Hidrolavadora 2200 PSI 1900 W K5 Karcher", "Hidrolavadora"),
                caso("Hormigonera Compact 130 Lts Deper", "Herramienta Eléctrica"),
                caso("Llave Cruz 16\" Datrak", "Herramienta Manual"),
                caso("Disco de Corte Metal 115 x 1.5 x 22.2 Stanley", "Accesorio de Herramienta"),
                caso("Lija al Agua N° 120 Hunter", "Lija"),
                caso("Caja Portaherramientas 27.5x15x20.8 Cm Metal HMROBCH168 Robust", "Caja de Herramientas"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("tools")
    @DisplayName("power and hand tools and their accessories")
    void toolsNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> hardware() {
        return Stream.of(
                caso("Tornillo Aguja T2 6x1 Cm x 200 Un Tel", "Tornillo"),
                caso("Tarugo Nylon S8 + Tornillo 8 Mm X 4 Un Fischer", "Tarugo"),
                caso("Clavo Punta Paris 2.1/2 X 1Kg", "Clavo"),
                caso("Alambre Negro Recocido N14 1Kg", "Fijación"),
                caso("Bisagra Cazoleta Gris 35 Mm con Codo 0° Eurotecno", "Bisagra"),
                caso("Kit Cerradura + Picaporte Doble Balancín Trabex", "Cerradura"),
                caso("Candado Latón 25 Mm Marrón 4 Un Pado", "Candado"),
                caso("Ménsula Doble 27 Cm Acero Blanco Sc Metalúrgica", "Herraje"),
                caso("Cinta Aisladora PVC Negro 20 Mts Tacsa", "Cinta Adhesiva"),
                caso("Guante de Vaqueta Nacional Amarillo DP", "Guante de Trabajo"),
                caso("Máscara de Soldar Polipropileno Ignífugo Negro Essential Silver Shadow", "Protección Personal"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("hardware")
    @DisplayName("fasteners, hinges, locks, tapes and protective gear")
    void hardwareNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> home() {
        return Stream.of(
                caso("Inodoro con Depósito 3003 Vessanti", "Sanitario"),
                caso("Grifería Cocina Monocomando Cromado Swift Peirano", "Grifería"),
                caso("Columna de Ducha Cromo 150 Cm 3F+1 Vessanti", "Ducha"),
                caso("Bañera 149x69x37,5 Cm Anatómica Bagnara", "Bañera"),
                caso("Mampara de Baño Recta 100x190 Cm Vidrio Templado Vessanti", "Mampara"),
                caso("Vanitory de Pie 52x83x36 Cm Melamina Blanco Alba Amube", "Vanitory"),
                caso("Bacha de Apoyar 41x33 Cm Porcelana Blanca Piazza", "Bacha"),
                caso("Toallero Repisa Bamboo 38x20 Cm Dansk", "Accesorio de Baño"),
                caso("Cómoda 6 cajones Mallorca madera/blanco 82x150x40 cm M+Design", "Mueble"),
                caso("Placard 176x47x180 Cm Blanco 6 Puertas 4 Cajones Nordik Strand Muebles Gacela", "Placard"),
                caso("Cama Box Moderna Blanca 200X164X39 Cm Dielfe", "Cama"),
                caso("Colchón Arno 1.5 Plazas 90x190x23 Cm Kavanag", "Colchón"),
                caso("Comedor Metal/Vidrio 4 Sillas+Mesa 100x100x73 Cm beige Pizza M+Design", "Mesa"),
                caso("Sillón Reclinable 66x88x100 Cm Gris Oscuro Moi M+Design", "Sillón"),
                caso("Estantería 90x183x45 Cm Metal Gris Sc Metalúrgica", "Estantería"),
                caso("Caja Organizadora 30 Lts Ratán Blanca Plásticos Poo", "Caja y Canasto"),
                caso("Basurero a Pedal 11,2 Lts Metálico Gris Cotidiana", "Basurero"),
                caso("Tendedero Extend Con Ruedas 18 M", "Tendedero"),
                caso("Set 20 Perchas Terciopelo Gris", "Colgador"),
                caso("Limpia Inodoros Adhesivo Con Activos 30 Grs Lysoform", "Artículo de Limpieza"),
                caso("Kit Sistema Modular Triple Grafito Hacks & Racks", "Sistema Modular"),
                caso("Espejo De Pared Blanco 125.6x34 Cm M+Design", "Espejo"),
                caso("Roller 120X165 Cm. Blackout Blanco", "Cortina"),
                caso("Plumón Sintetico Bicolor Mf 75-180 2P Azul Aqua", "Ropa de Cama"),
                caso("Toalla de Baño Poliéster Multicolor 60x120 Cm Arcoíris Cotidiana", "Toalla"),
                caso("Árbol de Navidad Verde 1.8 Mts 500 Ramas Tradicional Cotidiana", "Decoración"),
                caso("Frasco Vidrio 750 Ml M+Design", "Bazar"),
                caso("Escritorio Con Cajones 120 Cm Platinum", "Escritorio"),
                caso("Silla de Escritorio Ejecutiva 6868 97x55x50 Cm con Ruedas Negra M+Design", "Silla"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("home")
    @DisplayName("furniture, bath, kitchen, organization and textiles")
    void homeNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> garden() {
        return Stream.of(
                caso("Tierra Fértil 40 Lts Roots", "Tierra y Fertilizante"),
                caso("Maceta Fibrocemento 80x20x20 Cm Gris La Cioppa", "Maceta"),
                caso("Pulverizador a Presión 5 Lts Roots", "Pulverizador"),
                caso("Cortadora de Césped Eléctrica 1300 W con Recolector 3005051 Petri", "Máquina de Jardín"),
                caso("Sombrilla de Caño 3 Mts Gris Outzen", "Gazebo y Sombrilla"),
                caso("Reposera Aluminio 4 Posiciones 90x61x95 Cm Verde Outzen", "Reposera y Hamaca"),
                caso("Banqueta Plástico Simil Rattan 48 Cm Marrón Plásticos Poo", "Mueble de Exterior"),
                caso("Parrilla Chulengo a Leña 101X52X112 Cm Tromen", "Parrilla"),
                caso("Carbón de Quebracho Blanco 8 Kg Tagwood BBQ", "Carbón y Leña"),
                caso("Cobertor Para Parrillas 300X200X90 Cm Dekha Funda3049", "Accesorio de Parrilla"),
                caso("Pileta de Lona 4500 Lts 75X200X300 Cm 1055 Pelopincho", "Pileta"),
                caso("Rejilla para Pileta Universal Blanca x 2 Un Detalles", "Accesorio de Pileta"),
                caso("Carpa 200x120x100 Cm Alpes Iguazú", "Carpa"),
                caso("Bolsa de Dormir Poliéster 210x80 Cm Azul Magallanes Alpes", "Bolsa de Dormir"),
                caso("Conservadora 50 Lts 54,5X37,5X44,5 Cm Soprano", "Conservadora"),
                caso("Mosquetón Pera 5x50 Mm Gris 2 Un Gardex", "Artículo de Camping"),
                caso("Alimento para Perros Adulto 20 Kg Voraz", "Alimento para Mascota"),
                caso("Cama para Mascotas Rectangular 90X69 Cm Gris Rayas Pet's Fun", "Cama para Mascota"),
                caso("Piedras Sanitarias Higiénicas 2 Kg Pet's Class", "Higiene de Mascota"),
                caso("Correa Retráctil para Perros 3 Mts Negra Pet'S Fun", "Accesorio para Mascota"),
                caso("Piedra Decorativa 10 Dm3 Blanca Pietracor", "Decoración de Jardín"),
                caso("Limitador de Canteros Metálico 16x106 Natural", "Cerco y Media Sombra"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("garden")
    @DisplayName("garden, grill, pool, camping and pets")
    void gardenNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> automotive() {
        return Stream.of(
                caso("Aceite Full Sintético 5W40 1 Lt Goodyear", "Lubricante"),
                caso("Agua Desmineralizada 5 Lts Match 1", "Líquido Automotor"),
                caso("Batería para Auto 12X75 Cm Premium Max Herbo", "Batería de Auto"),
                caso("Escobilla 20 Pulgadas Universal Negro Goodyear", "Escobilla de Auto"),
                caso("Cubre Auto Gris 533X178X120 Cm XL Iael", "Accesorio de Auto"),
                caso("Crique Hidráulico 2 Toneladas Goodyear", "Herramienta de Auto"),
                caso("Matafuego Auto Compacto ABC 1 Kg Melisam", "Seguridad Vial"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("automotive")
    @DisplayName("automotive")
    void automotiveNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> appliances() {
        return Stream.of(
                caso("Termotanque Eléctrico 90 Lts 1500 W Blanco Te90 Vesubio", "Termotanque"),
                caso("Calefón a Gas Natural 14 Lts 23.03 Kw Gris DR14A Perpetua", "Calefón"),
                caso("Calefactor a Gas Natural 3000 Kcal Gris Peabody", "Calefactor"),
                caso("Ventilador de Techo 54\" 78 W 3 Velocidades Café KL-CFL5422LTRT-ac Klauben", "Ventilador"),
                caso("Aire Acondicionado Frio/Calor 3000 Fg Inverter 3750 W Phin37Ha2An Philco", "Aire Acondicionado"),
                caso("Heladera Inverter No Frost 397 Lts Blanca WRE44BBDIM Whirlpool", "Heladera"),
                caso("Lavarropas 6.5 Kg Carga Superior Blanco LSDR0680TB0 Drean", "Lavarropas"),
                caso("Cocina a Gas 47 Cm Marfil Cg 2025 Sirena", "Cocina y Horno"),
                caso("Extractor de Aire 100 Mm 12 W Blanco Hy-Vf100A Hydra", "Campana y Extractor"),
                caso("Aspiradora Wet and Dry 1600 W 30 Lts Inox ASPBR30LIN Robust", "Aspiradora"),
                caso("Freidora de Aire 5 Lts 1500W Gris y Negro AL-4801K Axel", "Pequeño Electrodoméstico"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("appliances")
    @DisplayName("appliances")
    void appliancesNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> sport() {
        return Stream.of(
                caso("Bicicleta Aluminio R29 Gris/Fucsia Skil", "Bicicleta"),
                caso("Bicicleta Trek Marlin 7 Rodado 27,5 Talle S", "Bicicleta"),
                caso("Scooter Aluminio 29x78 Cm Celeste Triscooter Radost", "Monopatín"),
                caso("Cadena Shimano CN HG53 116 Eslabones 9V", "Repuesto de Bicicleta"),
                caso("Kit Reparación Bicicleta 15x12 Cm M Wave Basic", "Repuesto de Bicicleta"),
                caso("Mancuerna Plástico 1 Kg Mir Fitness", "Pesas"),
                caso("Soga para Saltar Plastico 115 Grs Quuz", "Accesorio de Fitness"),
                caso("Kayak Individual Rosa Maori Sportkayaks", "Kayak y Bote"),
                caso("Aro de Básquetbol 90X60X43 Cm Radost", "Equipamiento Deportivo"),
                caso("Mesa Ping Pong Mini 90x40 Cm Radost", "Equipamiento Deportivo"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("sport")
    @DisplayName("bikes, fitness, kayaks and sports equipment")
    void sportNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> toys() {
        return Stream.of(
                caso("Muñeco Reno Beige 20X15 Cm M+Design", "Juguete"),
                caso("Cama Elástica Redonda 244 Cm 42 Resortes Radost", "Cama Elástica"),
                caso("Juego de Peces Aros 33,5x28,5x30 Cm Bestway", "Juguete"),
                caso("Muñeco Trepador Cascanuez Surtido 71 Cm M+Design", "Juguete"));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("toys")
    @DisplayName("toys")
    void toysNamesLandInTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("collisions")
    @DisplayName("words shared with another family do not steal the name")
    void collisionsKeepTheirCategory(String nombre, String esperado) {
        assertCategoria(nombre, esperado);
    }

    static Stream<Arguments> collisions() {
        return Stream.of(
                caso("Maza Trasera Bicicleta 32 Agujeros Shimano", "Repuesto de Bicicleta"),
                caso("Cadena Shimano CN HG53 116 Eslabones 9V", "Repuesto de Bicicleta"),
                caso("Mecha de Pelo Negra Para Peluca", "Otros"),
                caso("Mecha Madera 10 Mm Bremen", "Accesorio de Herramienta"),
                caso("Toalla adidas de Microfibra Magnética Players Cart", "Otros"),
                caso("Toalla Nike Deportiva Microfibra", "Otros"),
                caso("Carretilla Bebé Plegable Con Capota", "Otros"),
                caso("Carretilla Rueda de Goma 75 Lts Deper", "Herramienta de Jardín"),
                caso("Bolsa De Boxeo Proyec 90X35 cm Con Cadena", "Otros"),
                caso("Cortadora de Pelo Inalámbrica Philips", "Otros"),
                caso("Careta de Soldar Fotosensible Silver Shadow", "Protección Personal"),
                caso("Disco Para Pesas 5 Kg Fundición", "Pesas"));
    }

    @ParameterizedTest(name = "\"{0}\" is not {1}")
    @MethodSource("collisionsThatMustNotLand")
    @DisplayName("a keyword of one family does not capture a name that belongs elsewhere")
    void collisionsDoNotLandInTheWrongCategory(String nombre, String prohibida) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isNotEqualTo(prohibida);
    }

    static Stream<Arguments> collisionsThatMustNotLand() {
        return Stream.of(
                caso("Neumático Tubeless Tarugos MTB 29 x 2.2 Maxxis", "Tarugo"),
                caso("Guantes De Boxeo adidas Speed 50", "Guante de Trabajo"),
                caso("Guantes DRB Fitness Cromo 2.0 Unisex", "Guante de Trabajo"),
                caso("Maza Trasera Bicicleta 32 Agujeros Shimano", "Herramienta Manual"));
    }

    @ParameterizedTest(name = "\"{0}\" stays footwear")
    @MethodSource("footwearWithToolLikeNames")
    @DisplayName("running and training shoes whose model names look like other words stay footwear")
    void footwearStaysFootwear(String nombre) {
        assertThat(CategoryGroups.esCalzado(classifier.normalizarCategoria(null, nombre))).isTrue();
    }

    static Stream<String> footwearWithToolLikeNames() {
        return Stream.of(
                "Zapatillas Puma Softride Enzo Evo Mujer",
                "Zapatillas Reebok Nanoflex TR De Hombre");
    }

    @Test
    @DisplayName("sports names that start with a non-textile guard word keep their leisure-time category")
    void leisureFamiliesRunBeforeTheGuard() {
        assertCategoria("Aro de Básquetbol 90X60X43 Cm Radost", "Equipamiento Deportivo");
        assertCategoria("Juego de Peces Aros 33,5x28,5x30 Cm Bestway", "Juguete");
    }
}
