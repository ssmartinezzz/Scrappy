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
@DisplayName("Ropa de Cama y Colchón: product type for bedding, size in plazas for a mattress")
class CamaSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private static final String RC = "Ropa de Cama";
    private static final String CO = "Colchón";

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each bedding sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Juego de Sábanas Arredo Hojas Escondidas Queen Azul|Juego de Sábanas",
            "Juego Sábanas Twin Espalma Basic 180 Hilos Algodón Peinado|Juego de Sábanas",
            "Sabana Ajustable Delfin Super King Verde Malva|Juego de Sábanas",
            "Set 2 Sabanas 1.5 Pz mf 75 Gr Ecru|Juego de Sábanas",
            "Combo x2 Sábanas Algodón Saten 400H King - Blanco + Gris|Juego de Sábanas",
            "Acolchado Superman 1 1/2 PLAZA|Acolchado",
            "Cubrecama Arredo Reversible Lunares Twin Gris claro|Cubrecama",
            "Cover Las Calas Classic Queen 240x260cm colores varios|Cubrecama",
            "Quilt Liso Amarelo King Blanco|Cubrecama",
            "Almohada Viscoelástica Cervical con bastones 60 x 40 Espácity|Almohada",
            "Pack 2 Almohadas Friselina Blanco 40x70 Cm Cielo Esencial|Almohada",
            "Frazada Super Soft Infantil Paw Patrol|Frazada y Manta",
            "Manta polar liviana Argentina|Frazada y Manta",
            "Cobertor Azul 400x570 Cm Cec|Frazada y Manta",
            "Manta/Pie de Cama de Algodón 2.00mts x 1.00mts Comodohome|Frazada y Manta",
            "Plumón Arredo Reversible Liso King Blanco|Plumón",
            "Edredon Flannel Con Corderito Triple Capa Catana Home King|Plumón",
            "Funda de Almohada Algodón Blend Lisa|Funda",
            "Funda Nordica Twin Catana Home Con Funda Para Almohada|Funda",
            "Funda Plumón 2P 100 Gr Azul Melange|Funda",
            "Cubresommier Decoprint Almohar Gris King 200x200 cm|Protector",
            "Cubrecolchón Impermeable Jacquard|Protector",
            "Funda de Colchón Impermeable 2 Plazas|Protector",
    })
    void ropaDeCama(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, RC)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a mattress takes its size from the plazas or the dimensions in the name")
    @CsvSource(delimiter = '|', value = {
            "Colchón Espuma TNT 1 Plaza 80x190x17 Cm Camudi|1 Plaza",
            "Colchon Inducol Esmeralda 80X190|1 Plaza",
            "Colchón de Espuma Inducol Nimbus Firm 090x190|1 Plaza",
            "Colchón 1 Plaza Espuma Meyer Dream (080x190)|1 Plaza",
            "Colchón 1 Plaza y Media Resorte Serta Detroit Euro (090x190)|1 1/2 Plaza",
            "Colchón de espuma Zafiro Jaspe de 1 1/2 plazas 100x200 cm|1 1/2 Plaza",
            "Colchon de Espuma Inducol Limay 100X200|1 1/2 Plaza",
            "Colchón Power 1.5 Plazas 90x190x27 Cm Springwall|1 1/2 Plaza",
            "Colchón 2 Plazas Espuma Telgo Vida (140x190)|2 Plazas",
            "Colchon de Resortes King Koil Montecarlo 130X190|2 Plazas",
            "Colchón Piero Espuma Parma 140X190X20Cm|2 Plazas",
            "Colchon Inducol L.2000 Premium Em 1.30|2 Plazas",
            "Colchon de Resortes Inducol Classic Pocket 150X190|2 1/2 Plazas",
            "Colchón de Espuma de Alta densidad 2 1/2 Plazas Zafiro Linde 150x190|2 1/2 Plazas",
            "Colchón 2 Plazas y Media Resorte Sealy Linen (160x200)|2 1/2 Plazas",
            "Colchón de Espuma Zafiro Bremen Queen Size 160 x 200 cm|Queen",
            "Colchon King Koil Reserve Luxury Lexington 160X200|Queen",
            "Colchon Inducol Pyxis 1.60X0.24X2.00|Queen",
            "Colchón Compactado de Espuma Serta Max Comfort King Size 180 x 200 cm|King",
            "Colchon de Resortes King Koil XL Advanced Super King 200 x 200 cm|King",
            "Colchon King Koil Xl Advance 180X200|King",
            "Colchón Hotelero Resorte Inducol Delta (180x190)|King",
    })
    void colchonPorTamano(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, CO)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("sommier, conjunto and bicama of a size get the sub of the colchón of that size")
    @CsvSource(delimiter = '|', value = {
            "Sommier y Colchón de Resortes 2 Plazas Serta Joplin 130x190|2 Plazas",
            "Colchon + Sommier Inducol Blenda Azul 90X190|1 Plaza",
            "Colchón + Sommier Reno King 180x200x59 Cm Kavanag|King",
            "Sommier Baulera Roomy y Colchón Springwall Magnetic 200x200 - King|King",
            "Conjunto Colchon + Baul King Koil Montecarlo 140 X 190|2 Plazas",
            "Bicama sommier y colchón 1 plaza Serta Classic 90x190|1 Plaza",
            "Sommier + Colchon Inducol Jubilo Azul 1.00X0.20X1.90|1 1/2 Plaza",
    })
    void sommierYConjunto(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, CO)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("the brand King Koil and the bare word king do not make a king-size mattress")
    void kingKoilNoEsKing() {
        assertThat(subs.resolver("Colchon King Koil G22", CO)).isEmpty();
        assertThat(subs.resolver("Colchón de resortes King Koil 120 Gold Luxury Pocket", CO)).isEmpty();
        assertThat(subs.resolver("Colchon King Koil Brighton 80X190", CO)).isEqualTo("1 Plaza");
    }

    @Test
    @DisplayName("a mattress without a size, and what is not a mattress, keep no sub")
    void sinTamanoNiColchon() {
        assertThat(subs.resolver("Colchon Inducol Cardiel", CO)).isEmpty();
        assertThat(subs.resolver("Colchon de Espuma Inducol Infantil 60X120", CO)).isEmpty();
        // protector, bed, base and pet bed sit in the Colchón category by mistake: they have no size sub
        assertThat(subs.resolver("Protector De Colchón Impermeable 2 Plazas", CO)).isEmpty();
        assertThat(subs.resolver("Cama Pet Bed Springwall Colchón De Resortes Perros 76x100", CO)).isEmpty();
        assertThat(subs.resolver("Base Sommier 140Cm Dielfe", CO)).isEmpty();
        // a bath poncho, a robe, a cushion, a sleeping bag and a filling are not bedding sub-types
        assertThat(subs.resolver("Poncho de Toalla Toy Story Buzz Lightyear", RC)).isEmpty();
        assertThat(subs.resolver("Bata Flannel Hello Kitty", RC)).isEmpty();
        assertThat(subs.resolver("Almohadón Plush Cushion con Forma Sonic", RC)).isEmpty();
        assertThat(subs.resolver("Bolsa de Dormir Stitch", RC)).isEmpty();
        assertThat(subs.resolver("Relleno de Acolchado 4 Estaciones", RC)).isEmpty();
        // a chair cover is not bedding, and "juego" needs sábanas or cama after it
        assertThat(subs.resolver("Pack x6 Fundas de Silla Tusor Algodón Pesado", RC)).isEmpty();
        assertThat(subs.resolver("Juego de Mesa Cartas", RC)).isEmpty();
    }

    @Test
    @DisplayName("punctuation, doubled blanks and uppercase accents do not hide the leader or the size")
    void puntuacion() {
        assertThat(subs.resolver("COLCHÓN 1 PLAZA Y MEDIA RESORTE (090x190)", CO)).isEqualTo("1 1/2 Plaza");
        assertThat(subs.resolver("Colchón/Sommier 2 Plazas 140 x 190 Cm", CO)).isEqualTo("2 Plazas");
        assertThat(subs.resolver("  Colchon   Inducol   Pyxis 1.60X0.24X2.00", CO)).isEqualTo("Queen");
        assertThat(subs.resolver("Juego/Sábanas 1 1/2 Plaza Minnie", RC)).isEqualTo("Juego de Sábanas");
        assertThat(subs.resolver("JUEGO DE CAMA 2 PLAZAS ESTAMPADO", RC)).isEqualTo("Juego de Sábanas");
    }

    @Test
    @DisplayName("no sport sub leaks in, and blank or unknown input stays empty")
    void sinSubYVacio() {
        assertThat(subs.resolver("Sábana Running Tenis Pro", RC)).isEqualTo("Juego de Sábanas");
        assertThat(subs.resolver("Colchón Running Tenis Pro", CO)).isEmpty();
        assertThat(subs.resolver("", RC)).isEmpty();
        assertThat(subs.resolver(null, CO)).isEmpty();
        assertThat(subs.resolver("Colchón 1 Plaza 80x190", "Otros")).isEmpty();
    }
}
