package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category")
@DisplayName("Somos Rex and Sommier Center taxonomy pass: names that fell to Otros, and the shared rules that must not leak")
class MagentoHomeSitesTaxonomyTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String categoria(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("bedroom names of a bedding store land in their existing category")
    @CsvSource(delimiter = '|', value = {
            "Respaldo 2 Plazas Squad Sofía Gris Oscuro|Respaldo",
            "Respaldo King Liso Quios Marrón Squad|Respaldo",
            "Base Sommier Universal White 2 plazas (130x190)|Cama",
            "Base Sommier Diván 1 plaza y media (100x200)|Cama",
            "Diván Cama 1 Plaza y Media Inducol Delta (090x190)|Cama",
            "Promo 2x1 Almohadas Zlow Visco Basic Cervical|Ropa de Cama",
            "Promo 2x1 Almohada Memory Grid Gel Standard|Ropa de Cama",
    })
    void bedding(String nombre, String esperada) {
        assertThat(categoria(nombre)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("a bundle has no leading product noun, so it stays Otros")
    void bundlesStayOtros() {
        assertThat(categoria("Combo Dream Compact 160x200 + Som.Box + Respaldo Alba")).isEqualTo("Otros");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("hardware and paint names of a building store land in their existing category")
    @CsvSource(delimiter = '|', value = {
            "Recuplast Hidroesmalte Epoxi Satinado Gris 1 Lt Sinteplast|Pintura",
            "Cetol Classic Ultraprotector Satinado 10 L Natural|Pintura",
            "Esmalte Epoxi Negro Brillante IRAM 1198 4Lts Sinteplast|Pintura",
            "Latex Color Interior Exterior Polacrin 4 Lts Naranja|Pintura",
            "Recuplast Frentes Gris Impermeabilizante 10 Lts Sinteplast|Impermeabilizante",
            "Membrana en pasta transitable gris 20 kg|Impermeabilizante",
            "Vadex Texturado Romanica Medio Praga 25Kg|Revestimiento",
            "Enduplast Enduido Plástico Interior 25 Kg Sinteplast|Enduido",
            "Adhesivo Reparador Rápido 3M 4247|Adhesivo",
            "Poxiran Adhesivo De Contacto Sin Tolueno 225Gr|Adhesivo",
            "Repuesto Rodillo Marrón Satinado 22 Cm Lumar|Accesorio de Pintura",
            "Rodillo Mini Epoxi 5 Cm El Galgo|Accesorio de Pintura",
            "Pincel Persianero Nº7 El Galgo Para Látex y Pintura de Pared|Accesorio de Pintura",
            "3m Lija En Seco Blue 338u 400 (65491) X 10U|Lija",
            "Pack X50 Unidades Lija Al Agua 3M Grano 100|Lija",
            "Cinta Amarilla 3M Automotriz 18mm x 40mts|Cinta Adhesiva",
            "Espátula Metálica Serie 50 Nº 12 El Galgo|Herramienta Manual",
            "Disco Desbaste Doble A Element T27 115x6.4x22.2 mm|Accesorio de Herramienta",
            "Generador Naftero Gp2500 AVR 2KVA + Aceite 4T x1 Lt|Herramienta Eléctrica",
            "Llave de Impacto Inalámbrico Dewalt 20v Dcf887|Herramienta Eléctrica",
            "Aparejo Eléctrico 500Kg 900w Malacate|Herramienta Eléctrica",
            "Mixer Minipimer Atma 3 en 1 1000W|Pequeño Electrodoméstico",
            "Multiprocesadora Liliana 5 en 1 Simplix AM700|Pequeño Electrodoméstico",
            "Salamandra Calefactor Eco 7000 Tromen|Calefactor",
            "Mate Stanley Acero Inoxidable Térmico 236ml|Termo y Botella",
            "Lunchera Rígida Conservadora 6 Lts Azul Mor|Conservadora",
            "Lámpara Techo Colgante Leuk Charti Blanco E27 Metal|Iluminación",
            "Yeso Tuyango Proyectable SG 30kg|Cemento",
            "Pasta De Pulir Al Agua 1 Kg Autopolish|Limpieza de Auto",
    })
    void hardwareAndPaint(String nombre, String esperada) {
        assertThat(categoria(nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} is not paint, tool, adhesive or bedding")
    @DisplayName("the shared keywords do not take beauty, kitchen, audio and household names")
    @ValueSource(strings = {
            "Esmalte Vogue Rápido Secado",
            "Primer universal de pestañas MAGIC EYES",
            "Brocha Para Ojos Biselada Mia Colors Tools Rosa",
            "Fijador En Aerosol Biferdil Con Pantenol Y Siliconas 195 Ml",
            "Mixer Consola Livetrak 8 Canales Zoom L-8",
            "Prensa Hamburguesa Smasher 11 Cm Diam",
            "Generador Agua Hidrógeno Curety 102A SPE PEM 420ml",
            "Cepillo Electrico Limpia Zapatillas Atma 5 en 1 Recargable",
            "Compresor Inflador de Aire Gadnic IN304 150PSI",
            "Escuadra 20X25 Cm Metal Negro M+Design",
            "Membrana Tecno Subcobertura 120 Cm X 25 Mts",
    })
    void collateralStaysPut(String nombre) {
        assertThat(categoria(nombre)).isNotIn("Pintura", "Adhesivo", "Herramienta Manual", "Herramienta Eléctrica",
                "Pequeño Electrodoméstico", "Impermeabilizante", "Accesorio de Pintura", "Ropa de Cama");
    }
}
