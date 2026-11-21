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
@DisplayName("Bicicleta: sub-category is the bike type, from an explicit word or else the rodado")
class BicicletaSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private static final String BI = "Bicicleta";

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each type, from an explicit word of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Bicicleta Mtb Overtech Fortis R29 Aluminio Shimano|Mountain Bike",
            "Bicicleta Mountain Bike MTB Fire Bird Turbo 2023 Rodado 29|Mountain Bike",
            "Bicicleta Moutain Bike MTB Q5 Overtech R29|Mountain Bike",
            "Bicicleta Playera Full 6 Velocidades Paseo Dama Rodado 26|Paseo y Playera",
            "Bicicleta Vintage Paseo Clasica Rod 26 Dama Negra|Paseo y Playera",
            "Bicicleta Fire Bird City R26 T16 Dama 21 Vel. Aluminio|Paseo y Playera",
            "Bicicleta Ruta Carrera Fixie Doble Pared Rodado 28 Le Bike|Ruta",
            "Bicicleta Ruta 700c Aluminio 16 Velocidades|Ruta",
            "Bicicleta Urbana Raleigh Venture 3.0 R 27.5 21v|Urbana / Fixie",
            "Bicicleta Topmega Fixie Streeter R28 Acero 1vel|Urbana / Fixie",
            "Bicicleta Raleigh Urban 1.1 Rodado 28 Talle 19\" Aluminio|Urbana / Fixie",
            "Bicicleta BMX R20'' Racer Kids amarilla Futura|BMX",
            "Bicicleta Ailude Stunt Rodado 26 Shimano Cues 9v|BMX",
            "Bicicleta Plegable Raleigh Straight Rodado 20 Aluminio 6V|Plegable",
            "Bicicleta Electrica Nippongame Hyper 7vel Bateria Integrada|Eléctrica",
            "Bicicleta Eléctrica Plegable Dellorean Meridian 350W Rodado 20|Eléctrica",
            "Bicicleta Raleigh Rowdy Rodado 20 Azul Aluminio Niños|Infantil",
            "Bici Cross Infantil Firebird R 16 Nena Canasto|Infantil",
            "Triciclo Multifuncional Infanti JY-T07B Rojo|Triciclo",
            "Camicleta Balance Bike|Camicleta",
            "Bicicleta De Equilibrio Sin Pedales Bebesit Verde|Camicleta",
    })
    void cadaTipo(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, BI)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("with no type word the rodado decides, in every way stores write it")
    @CsvSource(delimiter = '|', value = {
            "Bicicleta R29 Randers Kawill|Mountain Bike",
            "Bicicleta R 29 Randers Kawill|Mountain Bike",
            "Bicicleta Rodado 29 Randers Kawill|Mountain Bike",
            "Bicicleta Raleigh Mojave 4.5 Aluminio Rod 29 15\"/S|Mountain Bike",
            "Bicicleta Raleigh Venture 3.0 R27.5 Aluminio 21 Velocidades|Mountain Bike",
            "Bicicleta Rodado 27,5 Aluminio Disco|Mountain Bike",
            "Bicicleta 29 Gibsons Turbo|Mountain Bike",
            "Bicicleta R12 Nathor con cantimplora|Infantil",
            "Bicicleta Hot Wheels Rodado 12|Infantil",
            "Bicicleta R16 Nordic 17755|Infantil",
            "Bicicleta 16'' Stark Naranja Team|Infantil",
            "Bicicleta R20 Randers Kurban|Infantil",
            "Bicicleta Dencar Cromer Con Canasto Rodado 18|Infantil",
    })
    void porRodado(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, BI)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("an explicit type beats the rodado, and a kid's word beats the type")
    void elTipoGanaAlRodado() {
        assertThat(subs.resolver("Bicicleta Paseo R29 Dama", BI)).isEqualTo("Paseo y Playera");
        assertThat(subs.resolver("Bicicleta BMX Freestyle Niños Rodado 20", BI)).isEqualTo("BMX");
        assertThat(subs.resolver("Bicicleta Plegable Rodado 20 Aluminio", BI)).isEqualTo("Plegable");
        assertThat(subs.resolver("Bicicleta de paseo R16 con cantimplora GTS azul", BI)).isEqualTo("Infantil");
        assertThat(subs.resolver("Bicicleta Mtb Overtech Q6 Kids R16", BI)).isEqualTo("Infantil");
        assertThat(subs.resolver("Bicicleta Fire Bird Mtb Lady 18 Velocidades Rodado 24", BI)).isEqualTo("Mountain Bike");
    }

    @Test
    @DisplayName("an adult R20, a bare R26 or R28 and a model with no signal keep no sub")
    void ambiguoQuedaVacio() {
        assertThat(subs.resolver("Bicicleta R20 Dama Adulto Aluminio", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta R20 Nordic Cross Varon 17758", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Fire Bird R26 T16 Hombre 21 Vel. Aluminio", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta R28 Gravity Madam", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Vairo Xr3.0 Mecánico 21s", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Trek Precaliber 20", BI)).isEmpty();
    }

    @Test
    @DisplayName("a stationary bike is exercise equipment: no bike type, and no later rule gives it one")
    void bicicletaFija() {
        assertThat(subs.resolver("Bicicleta Fija Magnética Max 150 kg Fitage Force", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta de Spinning Overtech 3.0 Fija 6 kg", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Recumbent Magnética Corplus", BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Indoor Mtb Spinning Rodado 29", BI)).isEmpty();
        assertThat(subs.resolver("Mini Bicicleta Fija Dehuka Pedalera Ancianos", BI)).isEmpty();
    }

    @Test
    @DisplayName("an accessory that leads with another word keeps no sub, even if it names a bicycle")
    void accesorios() {
        assertThat(subs.resolver("Kit De Casco Y Protecciones Para Bicicleta Camicleta", BI)).isEmpty();
        assertThat(subs.resolver("Candado Slp Cansl18-002", BI)).isEmpty();
        assertThat(subs.resolver("Inflador Para Bicicleta Mtb R29", BI)).isEmpty();
        assertThat(subs.resolver("Bolso Asiento Impermeable Slp Bolsiba-011", BI)).isEmpty();
        assertThat(subs.resolver("Caramañola Slp Carslsp-039", BI)).isEmpty();
    }

    @Test
    @DisplayName("punctuation, doubled blanks and uppercase do not hide the leader; no sport sub leaks in")
    void puntuacionYVacio() {
        assertThat(subs.resolver("Bicicleta/MTB R29 Aluminio 21 Vel", BI)).isEqualTo("Mountain Bike");
        assertThat(subs.resolver("BICICLETA MOUNTAIN BIKE RODADO 29", BI)).isEqualTo("Mountain Bike");
        assertThat(subs.resolver("  Bicicleta   Playera   Rodado   26  Dama", BI)).isEqualTo("Paseo y Playera");
        assertThat(subs.resolver("Balanza Running Tenis Pro", BI)).isEmpty();
        assertThat(subs.resolver("", BI)).isEmpty();
        assertThat(subs.resolver(null, BI)).isEmpty();
        assertThat(subs.resolver("Bicicleta Mtb R29", "Otros")).isEmpty();
    }
}
