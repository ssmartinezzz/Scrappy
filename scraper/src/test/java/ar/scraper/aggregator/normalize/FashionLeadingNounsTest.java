package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Names from the Las Pepas catalogue (2026-11-11) that used to fall in Otros, plus the cross-site negatives. */
class FashionLeadingNounsTest {

    private final CategoryClassifier clasificador = new CategoryClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "BLUSA CON LAZOS NOVA|Camisa",
            "BLUSA PLISADA ISSEY|Camisa",
            "TAPADO DE CUERO ANN 2.0 // OVINO ITALIANO|Campera",
            "TAPADO DE JACQUARD MYSTIC|Campera",
            "TRENCH DE MICROTUL RAMONA|Campera",
            "PARKA REVERSIBLE DE CUERO MOOK|Campera",
            "KIMONO SATINADO KATARA REVERSIBLE|Campera",
            "BABUCHA HOLGADA RINA BOTAN|Pantalón",
            "ENTERIZA BRUMA|Enterito",
            "CHATITA DE CUERO MELIA|Zapato",
            "GUILLERMINA DE CUERO IMPERIAL|Zapato",
            "CAPUCHA TEJIDA JANE|Bufanda",
            "PAÑUELO DE SEDA TULIP|Bufanda",
            "AROS ANNETTE|Bijouterie",
            "AROS MINI PAPILLON|Bijouterie",
            "COLLAR AURORA|Bijouterie",
            "CHOKER AVRIL|Bijouterie",
            "CHOCKER GWENDOLYN|Bijouterie",
            "ANILLO CHUNKY FLOWER|Bijouterie",
            "BROCHE SANDRO SHIMMER|Bijouterie",
            "SCRUNCHIE SALLY|Bijouterie",
            "HEBILLA LIM|Bijouterie",
            "Brazalete Dorado Mujer|Bijouterie",
            "Pulsera Perlas Mujer|Bijouterie"
    })
    void fashionNounsThatFellInOtros(String nombre, String esperada) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} is not {1}")
    @CsvSource(delimiter = '|', value = {
            "Collar para Perros Guau Azahar|Bijouterie",
            "Remera con Aros Estampados|Bijouterie",
            "Camisa Blusa Oxford|Bijouterie",
            "Aro de Basquet Reglamentario|Bijouterie",
            "Pin Corona Hembra para Tablero|Bijouterie",
            "Anillo de Goma para Canilla|Bijouterie",
            "Collar de Perro Reforzado|Bijouterie",
            "collar cuero talla m cafe|Bijouterie",
            "ANILLO RELOJ CASIO G-SHOCK DWN-5600-1D - Black|Bijouterie",
            "Pañuelos Descartables Doble Hoja 21x21 Cm 10 Un 6 Paquetes Felpita|Bufanda",
            "Pañuelos Doble Hoja 75 Un Felpita|Bufanda",
            "Collar Para Perros 35 Cm Marrón CEC|Bijouterie",
            "Collar para Gatos 25-40 Cm Rosa Masai Pet's Fun|Bijouterie",
            "Broches para Ropa Plástico 16 Un Bettanin|Bijouterie",
            "Broches Media Sombra Negro 20 Un 6 x12 Cm Cec|Bijouterie",
            "Anillos de Sellado Surtido 130 Piezas Maxhaus|Bijouterie",
            "Pulsera Inteligente Dellorean D-Health Smartband IP68 Fitness|Bijouterie",
            "Pantalon Babucha Hombre|Campera"
    })
    void leadingNounOnlyAndOtherFamiliesWin(String nombre, String noEs) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isNotEqualTo(noEs);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Perfume para Auto Invictus 15 Cm Air Perfum",
            "Perfume para Auto Manzana 50 Cc Silisur",
            "Perfume para Auto New Car 50 Grs Silisur",
            "Aromatizante para Auto Vainilla"
    })
    void carFreshenersAreNotPerfume(String nombre) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo("Limpieza de Auto");
    }

    @Test
    void aFragranceIsStillAPerfume() {
        assertThat(clasificador.normalizarCategoria("", "Perfume Mujer Eau de Parfum 100ml")).isEqualTo("Perfume");
    }

    @Test
    void bijouterieIsInTheCanonAndDressedAsClothing() {
        assertThat(CategoryGroups.canonicalCategories()).contains("Bijouterie");
        assertThat(CategoryGroups.esIndumentariaOCalzado("Bijouterie")).isTrue();
    }
}
