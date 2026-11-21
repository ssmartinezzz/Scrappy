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
@DisplayName("Iluminación y Mueble: sub-category from the leading noun, in Argentine words")
class HogarSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private static final String IL = "Iluminación";
    private static final String MU = "Mueble";

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each lighting sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Velador Honguito E27 1 Luz Verde 13 W Abitare|Velador",
            "Lámpara Escritorio Led Negro Arián M+Design|Velador",
            "Lámpara De Mesa Amelia 1 Luz E27 Carilux|Velador",
            "Luz de Noche Azul Gadnic Velador LED Infantil|Velador",
            "Lámpara de Pie Trípode E27 Negro Natural Praga M+Design|Lámpara de Pie",
            "Luz De Pie Rgb Para Ambientación Altura 42cm X 2 Torres|Lámpara de Pie",
            "Colgante Industrial 4 Luces E27 Negro Cobre 511/A4 Faroluz|Colgante",
            "Lampara Colgante Philips Hue Con Filamento Globo G95 E27 Rgb|Colgante",
            "lamp colgar led alula ar|Colgante",
            "Plafón Cuadrado 1 Luz E27 Surtido M+Design|Plafón",
            "Led Plafón Redondo 12 W Luz Cálida Candela|Plafón",
            "Aplique Pared Vidrio 2 Luces E27 Blanco Satinado 40 Cm Ferrolux|Aplique",
            "Tortuga PVC Poli Redonda 1 Luz E27 Negro Faroluz|Aplique",
            "Lampara Exterior Ip65 Macroled Aplique Cuadrado Blanco|Aplique",
            "Spot Dicro Led 1 Luz GU10 Blanco JC Iluminación|Spot",
            "Cabezal Móvil Antideslumbre Dim 5w 24 Riel Skyline Diseño|Spot",
            "Proyector Led 30 W Luz Fría IP65 Candela|Reflector",
            "Kit x2 Reflector Led Blanco 50W Exteriores Interiores|Reflector",
            "Panel Led Cuadrado Embutir 1 Luz Led Integrado Luz Neutra 18 W|Panel y Listón",
            "Listón Led 12 W 92 Cm Luz Fría Candela|Panel y Listón",
            "Tira Led Luz Fría 28 W 5 Mts Ecokit Ledvance|Tira LED",
            "Philips Hue Tira Led Base Plus V4 De 2 Metro Smart|Tira LED",
            "Guirnalda Exterior Solar LED Luz Cálida 8 Luces 255 Cm|Guirnalda",
            "Cortina Led 3x2 Mts Blanco Frio 16 Tiras Deco|Guirnalda",
            "Farol Colonial Pared Rectangular Chapa San Justo|Farol y Estaca",
            "Estaca LED Exterior 7 W Luz Cálida Tbcin|Farol y Estaca",
            "Luz Emergencia 30 Led 8 Hs Macroled|Luz de Emergencia",
            "Portalámpara Cerámico E27 Blanco|Portalámpara",
            "Kit Florón y Portalámparas E27 Negro Elkas|Portalámpara",
    })
    void iluminacion(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, IL)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("the second noun decides when the leader is ambiguous (lámpara, led, luz)")
    @CsvSource(delimiter = '|', value = {
            "Lámpara LED E27 9W Luz Cálida|Lamparita",
            "Lampara Dicroica Led 5w Gu10 Fria 6000k Macroled|Lamparita",
            "Led Filamento Cálida E27 8 W Globo Macroled|Lamparita",
            "Pack X3 LED Classic 9W 720Lm E27 Fria 12.000Hs|Lamparita",
            "Led Bulbo Cálida E27 7 W Sica|Lamparita",
            "Luz Led Bulbo Fría E27 30 W Candela|Lamparita",
            "Pack x12 Lampara Gu10 Smart Led Philips Hue White Ambiance|Lamparita",
            "Led Reflectora Cálida E27 7 W Sica|Lamparita",
            "Led Reflector Luz Fría R7S 11W 118mm SICA|Reflector",
            "Lámpara Velador Mesa De Luz Cilíndrico Gris|Velador",
            "Lámpara de Pie Led Moderna|Lámpara de Pie",
            "Lampara Modulo Skyline Luz Calida Panal 33cm 16w Para Riel Blanco|Spot",
            "Philips Hue Plafón Led Inteligente Centris 3 Spots Blanco|Plafón",
            "Lámpara Led Bajo Consumo E27 15 W|Lamparita",
    })
    void liderAmbiguo(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, IL)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each furniture sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Alacena Mosconi Express 1.40 m Melamina Venezia|Alacena",
            "Bajo Mesada Mosconi 10239|Bajo Mesada",
            "Mueble Bajo Mesada Glamy 1 Puerta 60cm Blanco|Bajo Mesada",
            "Despensero Muebles Orlandi Jacaranda 3425|Despensero",
            "Organizador despensero Multiuso Madesa 1 Puerta 182 CmX60 Cm|Despensero",
            "Portahorno 210x60x57.8 Cm Melamina 2 Cajones 5 Estantes Pino|Portahorno",
            "Mueble Para Microondas Dielfe Axm060|Portahorno",
            "Rack de Tv Delos Dl881ng/Gs|Rack de TV",
            "Rack Mueble TV CON LUZ LED HELSINKI Barak Muebles|Rack de TV",
            "Mueble Para Tv Delos Vision Dvs05rnb Para Tv de 65\"|Rack de TV",
            "Panel Rack Para Tv Delos Rod Dro01rnbl|Rack de TV",
            "Centro de Entretenimiento 183x180,8x38,5 Cm MDF Blanco|Rack de TV",
            "Biblioteca Con Estantes Melamina Mosconi Librero Avellana|Biblioteca",
            "Comoda Muebles Orlandi Premium 293|Cómoda y Cajonera",
            "Cajonera Industrial de 3 cajones CPI003 Dielfe|Cómoda y Cajonera",
            "Chifonier Mosconi 157076|Chifonier",
            "Chiffonier Emc Chiper8|Chifonier",
            "Aparador Mosconi Triplo|Aparador",
            "Vajillero Delos Alfa|Vajillero",
            "Mesa de Luz Mosconi 56668|Mesa de Luz",
            "Mesa Mesita De Luz De Diseño Moderna - Madera - Melamina - Blanco|Mesa de Luz",
            "Zapatero Komfy - 6 pares|Zapatero",
            "Botinero Muebles Orlandi 148,5x70x25 Cm|Zapatero",
            "Organizador de Zapatos Extensible 3 Estantes Acero Inoxidable|Zapatero",
    })
    void mueble(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, MU)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a combo or a kit of kitchen furniture is a Cocina Modular; otherwise the first piece it names wins")
    @CsvSource(delimiter = '|', value = {
            "Combo De Cocina Alacena + Bajo Mesada Madesa Glamy 180cm|Cocina Modular",
            "Combo Cocina Esquina Bajo Mesada + Alacena + Cava + Despensero|Cocina Modular",
            "Mueble De CocinaMadesa Emilly Con Bajo Mesada, Alacena Y Despensero|Cocina Modular",
            "Set Muebles Cocina 180x225x60 Cm MDP 13 Espacios|Cocina Modular",
            "Kit 2 Muebles De Cocina Alacena Y Mostrador Reims 120 Cm|Cocina Modular",
            "Modular Aparador Flotante Kit De Cocina Compacto 120cm Easy|Cocina Modular",
            "Combo Completo De Cocina Emilly Marrón/Negro Madesa|Cocina Modular",
            "Combo Aparador Vajillero + Organizador Estante Multiuso|Aparador",
            "Combo 2 Bajo Mesadas Con Mesada 2 Puertas 3 Cajones Madesa|Bajo Mesada",
            "Combo 2 Mesas de Luz Flotantes|Mesa de Luz",
            "MÓDULO BAJO MESADA 40 cm. EXPRESS MELAMINA GABINETE COCINA Blanco|Bajo Mesada",
    })
    void combos(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, MU)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("a word glued to punctuation, doubled blanks and an uppercase accent do not hide the leader")
    void puntuacion() {
        assertThat(subs.resolver("Mueble/Bajo Mesada Glamy 1 Puerta", MU)).isEqualTo("Bajo Mesada");
        assertThat(subs.resolver("Rack:Tv Dielfe", MU)).isEqualTo("Rack de TV");
        assertThat(subs.resolver("  Mesa  de  Luz  Mosconi", MU)).isEqualTo("Mesa de Luz");
        assertThat(subs.resolver("Lámpara/Escritorio Led Flexible", IL)).isEqualTo("Velador");
        assertThat(subs.resolver("LÁMPARA COLGANTE E27", IL)).isEqualTo("Colgante");
        assertThat(subs.resolver("Colgante:Industrial 3 Luces E27", IL)).isEqualTo("Colgante");
    }

    @Test
    @DisplayName("a word that only starts like a leader or sits later in the name does not claim the row")
    void noReclamaLoQueNoEs() {
        // "bajo" leads here but is not a bajo mesada, and "consumo" is a lighting term, not furniture
        assertThat(subs.resolver("Kit Bajo Cama Mosconi 81028", MU)).isEmpty();
        assertThat(subs.resolver("Bajo Consumo Estante Organizador", MU)).isEmpty();
        // a video projector and a night-light projector are not a floodlight
        assertThat(subs.resolver("Proyector Epson Powerlite W49 3800 Ansi", IL)).isEmpty();
        assertThat(subs.resolver("Proyector XGIMI MoGo 4 Laser Coleccion", IL)).isEmpty();
        assertThat(subs.resolver("Proyector De Galaxia Astronauta Gadnic Luz Nocturna", IL)).isEmpty();
        assertThat(subs.resolver("Panel Led Matrix 35cm Programable Ojos Auto", IL)).isEmpty();
        assertThat(subs.resolver("Luz Led de Cultivo Indoor Gadnic Grow5 Colgante 24w", IL)).isEmpty();
        // switches and outlets belong to the Llave de Luz category: a sub here would hide the misclassification
        assertThat(subs.resolver("Llave De Luz De Dos Teclas Diseno Moderno 10 A 250v", IL)).isEmpty();
        assertThat(subs.resolver("Tomacorriente Y Tecla 1 Canal Macroled Milan Blanco 10a", IL)).isEmpty();
        assertThat(subs.resolver("Interruptor Usb Y Usb-c Integrado Macroled Negro", IL)).isEmpty();
        // a dining or coffee table, a wardrobe and a corner unit keep no sub: they have a category of their own
        assertThat(subs.resolver("Mesa Ratona Milenial Mesa De Centro Living", MU)).isEmpty();
        assertThat(subs.resolver("Placard Velvet 2 Puertas Corredizas 1,80 Mts", MU)).isEmpty();
        assertThat(subs.resolver("Esquinero Eco Lino Gris Oscuro Anubis Decoracion", MU)).isEmpty();
        assertThat(subs.resolver("Mueble Esquinero Organizador Con Estantes Zapatero y Perchero", MU)).isEmpty();
        assertThat(subs.resolver("Cava Alacena Mueble Porta botellas Madesa", MU)).isEmpty();
    }

    @Test
    @DisplayName("no sport sub leaks in, and blank or unknown input stays empty")
    void sinSubYVacio() {
        // tier 2 would read "running" / "tenis" here; neither category takes a sport sub
        assertThat(subs.resolver("Lámpara Running Tenis Pro", IL)).isEmpty();
        assertThat(subs.resolver("Balanza Running Tenis Pro", MU)).isEmpty();
        assertThat(subs.resolver("", IL)).isEmpty();
        assertThat(subs.resolver(null, MU)).isEmpty();
        assertThat(subs.resolver("Rack de Tv Delos", "Otros")).isEmpty();
    }
}
