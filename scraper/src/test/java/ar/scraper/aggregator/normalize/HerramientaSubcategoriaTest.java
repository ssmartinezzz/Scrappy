package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Sub-category")
@DisplayName("Herramienta Manual: sub-category from the leading noun, as a ferretería names it")
class HerramientaSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private static final String HM = "Herramienta Manual";

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Pinza Pico De Loro 10 250mm Fosfatizada Labor|Pinza y Alicate",
            "Alicate Corte Oblicuo Aislada Labor 5 Pulgadas|Pinza y Alicate",
            "Tenaza Carpintero 8 Pulgadas Robust|Pinza y Alicate",
            "Destornillador Plano 5x150 Mm Stanley|Destornillador y Puntas",
            "Puntas Hexagonales 300 Un Biassoni|Destornillador y Puntas",
            "Llave Francesa Ajustable 300mm 12 Pulgadas Jadever|Llave y Tubo",
            "Llave Combinada Con Crique Cr-v Corta 8mm|Llave y Tubo",
            "Llave Crique 1/2\" Mango de Goma Robust|Llave y Tubo",
            "Tubo Bocallave Hexagonal 12mm Cr-v Enc Cuadrado 1/2|Llave y Tubo",
            "Dado 12 Puntas 1/2\" x 22 Mm Stanley|Llave y Tubo",
            "Martillo Para Techista magnético Mango de Fibra 500 Gr|Martillo y Maza",
            "Maza De Goma 450gr|Martillo y Maza",
            "Nivel de Aluminio 610 Mm 3 Burbujas Crossmaster|Medición",
            "Cinta Métrica 3 Mts Stanley|Medición",
            "Escuadra Magnética para Soldar 3\" Daewoo|Medición",
            "Calibre Digital Hamilton|Medición",
            "Tester Multimetro Digital 9999 Conteo 1000v|Medición",
            "Nivel Láser Verde 15 Mts + Puntos Plomada Bosch|Medición",
            "Nivel Laser A Bateria 360 3 Linea 20v|Medición",
            "Llana Dentada 10x10 Mm Mota|Albañilería",
            "Fratacho De Telgopor 28 Cm|Albañilería",
            "Cuchara de Albañil Forjada 8\" Bremen|Albañilería",
            "Espátula Flex 125 Mm Mota|Espátula y Rasqueta",
            "Rasqueta Con Mango 10 Cm|Espátula y Rasqueta",
            "Serrucho Carpintero Broktools 20'' Doble Filo|Corte y Limado",
            "Arco de Sierra 150 Mm Crossmaster|Corte y Limado",
            "Tijera Corta Chapa Punta Curva 250mm|Corte y Limado",
            "Cutter 18 Mm Retractil|Corte y Limado",
            "Formón Para Carpintero 25 Mm|Corte y Limado",
            "Lima Gruesa Semi Redonda 10\" Robust|Corte y Limado",
            "Cortafríos Plano 300 X 35 Mm Biassoni|Corte y Limado",
            "Cepillo de Carpintero con Mango de Plástico Azul|Corte y Limado",
            "Set 2 Hojas Sierra para Metal 4-6-10 Dientes Sinpar|Corte y Limado",
            "Prensa Sargento Tipo C 6 PuLG Sujecion 150mm|Prensa y Morsa",
            "Morsa De Banco N4 Robust|Prensa y Morsa",
            "Pistola aplicadora para cartuchos 600 ml|Pistola de Silicona",
            "Remachadora Pop Manual 240 Mm Crossmaster|Engrampadora y Remachadora",
            "Engrapadora Metalica Manual 4 A 8mm Adegso|Engrampadora y Remachadora",
            "Set de Herramientas 40 Piezas Con Maletín|Juego y Set",
            "Caja De herramientas Metálica 99 Piezas|Juego y Set",
    })
    void cadaSub(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, HM)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a set, a kit or a juego is read from the tool that follows")
    @CsvSource(delimiter = '|', value = {
            "Juego de Destornilladores Hamilton De06|Destornillador y Puntas",
            "Set Destornilladores Dielectricos Electricista Aislado 1000v|Destornillador y Puntas",
            "Juego de Bocallaves Hamilton Jb90|Llave y Tubo",
            "Juego De 20 Piezas Bocallaves Y Accesorios Lusqtoff|Llave y Tubo",
            "Kit Completo 5 Llaves Tubo De Mano En L Jadever|Llave y Tubo",
            "Juego 9 Llaves Allen L Punta Esferica Milwaukee|Llave y Tubo",
            "Set de Pinzas 8\" 6\" 6,5\" 3 Piezas Crossmaster|Pinza y Alicate",
            "Set de Cutters 9 Mm y 18 Mm Naranja|Corte y Limado",
            "Set de Herramientas Manual 6 Piezas 2 Alicates + 4 Destornilladores|Juego y Set",
            "Set de Herramientas de Precisión 24 Piezas + Pinzas y Destornilladores|Juego y Set",
            "Pack X 6 Destornilladores Jadever Planos Y Philips|Destornillador y Puntas",
            "Mini Alicate De Corte Diagonal 4.5 Adegso|Pinza y Alicate",
            "Kit 10 Hojas Repuesto Cutter Trincheta 25mm Milwaukee|Corte y Limado",
    })
    void juegosYKits(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, HM)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("what is not a hand tool, or not one of these families, keeps no sub")
    void sinSub() {
        // a llave de paso or de luz, an adhesive or teflon tape, a wire brush, a bit set: another category, or no family here
        assertThat(subs.resolver("Llave de Paso Esférica 1/2 Bronce", HM)).isEmpty();
        assertThat(subs.resolver("Llave de Luz Doble Tecla Blanca", HM)).isEmpty();
        assertThat(subs.resolver("Cinta Teflón Sella Hilo Agua 1/2'' 10 m", HM)).isEmpty();
        assertThat(subs.resolver("Cepillo de Alambre Manual 18,5 Cm Silver Shadow", HM)).isEmpty();
        assertThat(subs.resolver("Juego de Mechas Y Puntas 100 Piezas Lusqtoff", HM)).isEmpty();
        // a box that is not a toolbox, and a toy "dado"
        assertThat(subs.resolver("Caja de Inglete Plástica 35,5 Cm", HM)).isEmpty();
        assertThat(subs.resolver("Dados de Goma 6.5 Cm Multicolor Pet's Fun", HM)).isEmpty();
        // a caulking gun with a battery and an air gun are not the manual one
        assertThat(subs.resolver("Pistola Calafatera 18V TESG 18/10 LI Einhell", HM)).isEmpty();
        assertThat(subs.resolver("Pistola Para Sopletear Pico Corto 16mm", HM)).isEmpty();
    }

    @Test
    @DisplayName("a power tool misfiled in the category keeps no sub, but an electrician's insulated tool does")
    void herramientaElectrica() {
        assertThat(subs.resolver("Sierra De Mano Circular Sin Bateria 185mm Brushless 18v", HM)).isEmpty();
        assertThat(subs.resolver("Llave Crique Brushless 1/2pLG Inalambrica 18v 120nm", HM)).isEmpty();
        assertThat(subs.resolver("Llave Crique Neumatica Encastre 3/8 81nm 90 Psi", HM)).isEmpty();
        assertThat(subs.resolver("Kit de destornillador electrico de 82 piezas", HM)).isEmpty();
        assertThat(subs.resolver("Juego de Herramientas de 119 Piezas Con Taladro 20v", HM)).isEmpty();
        assertThat(subs.resolver("Engrampadora Neumática 12,8 Mm Konan", HM)).isEmpty();
        assertThat(subs.resolver("Set de Destornilladores Dielectricos Electricista Aislado 1000v", HM)).isEqualTo("Destornillador y Puntas");
        assertThat(subs.resolver("Set de Herramientas para Electricista 7 Piezas 1000V Robust", HM)).isEqualTo("Juego y Set");
    }

    @Test
    @DisplayName("punctuation, doubled blanks and uppercase do not hide the leader; no sport sub leaks in")
    void puntuacionYVacio() {
        assertThat(subs.resolver("Juego/Destornilladores Hamilton De06", HM)).isEqualTo("Destornillador y Puntas");
        assertThat(subs.resolver("Set:Alicate y Pinza Robust", HM)).isEqualTo("Pinza y Alicate");
        assertThat(subs.resolver("  Set   de   Herramientas   100   Piezas", HM)).isEqualTo("Juego y Set");
        assertThat(subs.resolver("JUEGO DE BOCALLAVES HAMILTON JB90", HM)).isEqualTo("Llave y Tubo");
        assertThat(subs.resolver("Balanza Running Tenis Pro", HM)).isEmpty();
        assertThat(subs.resolver("", HM)).isEmpty();
        assertThat(subs.resolver(null, HM)).isEmpty();
        assertThat(subs.resolver("Martillo Maza De Goma", "Otros")).isEmpty();
    }
}
