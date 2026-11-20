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
@DisplayName("Pequeño Electrodoméstico y TV y Audio: sub-category from the leading noun")
class ElectroTvAudioSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private static final String PE = "Pequeño Electrodoméstico";
    private static final String TV = "TV y Audio";

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each appliance sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Cafetera Nespresso Creatista Plus|Cafetera",
            "Bialetti Moka Express 6 Cups Cafetera Italiana|Cafetera",
            "Maquina de Cafe Espresso Oster|Cafetera",
            "Licuadora Philips Daily HR212900 550w|Licuadora y Exprimidor",
            "Exprimidor Lüsqtoff 700 ml 65 W|Licuadora y Exprimidor",
            "Ninja Blast Max Portable Blender 570ml navy|Licuadora y Exprimidor",
            "Batidora Planetaria Liliana Mixtime Roja|Batidora y Mixer",
            "Mixer Manual Liliana AH300 450W|Batidora y Mixer",
            "Procesadora Philips Series 3000 750W|Procesadora",
            "Picadora De Carne Gadnic PC4500|Procesadora",
            "Robot de Cocina Moulinex Click & Cheff|Procesadora",
            "Pava Eléctrica Liliana AP925N sin corte 1.7lt|Pava Eléctrica",
            "Jarra Eléctrica Electrolux Eek50|Pava Eléctrica",
            "Tostadora Liliana AT900 Tostler 750W|Tostadora y Sandwichera",
            "Mini Waflera Electrica Diseno Corazon|Tostadora y Sandwichera",
            "Freidora de Aire Atma FR248AW Digital 8 lt|Freidora",
            "AIR FRYER XIAOMI - DUAL ZONE - 10L|Freidora",
            "Horno Philips AI55107 Airfryer 12L|Freidora",
            "Secador de pelo Bellissima C19 2000W|Secador de Pelo",
            "Cepillo Secador Modelador Shark Ht212eu|Secador de Pelo",
            "Planchita GA.MA Slice|Planchita y Rizador",
            "Plancha de Cabello Philips Hp8321/00|Planchita y Rizador",
            "Rizador Remington Air Vive Ci8930|Planchita y Rizador",
            "Plancha a Vapor Philips DST612020 2200W|Plancha de Ropa",
            "Plancha Seca Philco Psp1217n|Plancha de Ropa",
            "Estacion de planchado Oster GCSTSG15-054|Plancha de Ropa",
            "Afeitadora Philips S1881/00 Serie 1000|Afeitadora y Cortadora",
            "Máquina de Cortar Pelo Wahl Homecut Clipper|Afeitadora y Cortadora",
            "Cortadora GA.MA GBS Absolute Style|Afeitadora y Cortadora",
            "Depiladora Philips IPL Lumea Prestige|Depiladora",
            "Maquina Cortadora de Pelo Patillera Kemei KM-1971A|Afeitadora y Cortadora",
            "Máquinas Para Afeitar Prestobarba3 Cool|Afeitadora y Cortadora",
            "Maquina Depiladora Facial Con LED Recargable|Depiladora",
    })
    void pequenoElectro(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, PE)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each audio / TV sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Parlante Portatil Philips TAX3206/77 BT 40W|Parlante",
            "Bafle Activo Con Bluetooth Samson Rs115a|Parlante",
            "Torre de Sonido Noblex Mnt1850|Parlante",
            "Sistema portátil LG XBOOM XL5S 200W bluetooth|Parlante",
            "Smart Tv Qled Titan Os Philips 65\" 4K|Televisor",
            "75\" The Frame LS03D|Televisor",
            "Barra de Sonido Samsung HW-Q600C 3.1.2|Barra de Sonido",
            "Sound Bar Aiwa Aw-Bs782n 90w|Barra de Sonido",
            "Proyector Epson Powerlite W49 3800 Ansi|Proyector",
            "Soporte Tv Led Movil Tagwood Hstv34|Soporte",
            "Tripode para parlante Joog HS-01 1,20m|Soporte",
            "Handy Baofeng BF-999S 5w 16CH UHF|Handy",
            "Consola Behringer Xenyx Qx1222Usb Mixer|Consola y Mixer",
            "CONTROLADOR DJ PIONEER DDJ-REV1 NEGRO|Consola y Mixer",
            "Bandeja Tocadiscos Audio Technica At-lp60x-bw|Tocadiscos",
            "Radio Portatil Aiwa Aw-Rp101|Radio",
            "Minicomponente LG Cj45 Rms7202|Equipo de Música",
            "Sistema Audio Panasonic Sc-Max6500|Equipo de Música",
    })
    void tvYAudio(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, TV)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("the leading noun wins over a word that comes later in the name")
    @CsvSource(delimiter = '|', value = {
            "Soporte para Smart TV 65\" Nakan|Soporte|" + TV,
            "Soporte De Pared Para Parlante Bluetooth|Soporte|" + TV,
            "Barra de Sonido para Smart TV Samsung|Barra de Sonido|" + TV,
            "Smart Neo Qled Tv + Barra de Sonido Samsung 43\"|Televisor|" + TV,
            "Smart Tv Box Panacom Tvb15216 Uhd 4k Android||" + TV,
            "Smart TV box 4k 2Gb ram y 8Gb -hdmi -WiFi -c/control remoto -Android 10||" + TV,
            "Smart TV Joog 43 A4300JTV Full HD/ISDBT/Android + Soporte Fijo|Televisor|" + TV,
            "Parlante para Proyector Gadnic|Parlante|" + TV,
            "Bandeja para DVD o TVBOX en la pared|Soporte|" + TV,
            "Accesorio Tapa Para Licuadora Smartlife BL1008|Accesorio y Repuesto|" + PE,
            "Repuesto Embudo Cafetera 12 Pocillos Bialetti|Accesorio y Repuesto|" + PE,
            "Filtro de Papel para Cafetera Timemore|Accesorio y Repuesto|" + PE,
            "Kit de Filtros para Aspiradoras Ergorapido|Accesorio y Repuesto|" + PE,
            "Jarra de vidrio Electrolux 0,6 litros para cafetera CMB31|Accesorio y Repuesto|" + PE,
            "Cortadora de Fiambre Ultracomb FS6301||" + PE,
            "Plancha de asar Oster CKSTGD10MBKD||" + PE,
            "Secador de unas Kepma Mini Lampara LED UV||" + PE,
    })
    void elSustantivoLiderGana(String nombre, String esperado, String categoria) {
        assertThat(subs.resolver(nombre, categoria)).isEqualTo(esperado == null ? "" : esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a combo, a kit or a set is read from the product it holds first")
    @CsvSource(delimiter = '|', value = {
            "Combo Licuadora + Batidora Oster|Licuadora y Exprimidor",
            "Combo Batidora + Licuadora Oster|Batidora y Mixer",
            "Combo Plancha + Secador de Cabello Atma Ck4010n|Planchita y Rizador",
            "Combo Secador + Planchita CK4010P Atma|Secador de Pelo",
            "Set Cafetera Prensa Francesa Y Molinillo Waggs|Cafetera",
            "Pack x 2 Pava Eléctrica Liliana|Pava Eléctrica",
            "Kit Cortabarba/Pelo Philco Cb8875|Afeitadora y Cortadora",
            "Gama Italy Secador Mistral Keration|Secador de Pelo",
    })
    void combosKitsYSets(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, PE)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a sale prefix or a pack word is skipped before the leader is read")
    @CsvSource(delimiter = '|', value = {
            "Kit x 6 Handy Baofeng BF-999S 5w|Handy",
            "Kit x10 Handy Baofeng BF-888S|Handy",
            "Pack 2 Parlantes Bluetooth Noblex|Parlante",
            "Mini Proyector Portátil 4k Android 11|Proyector",
    })
    void prefijosDeVenta(String nombre, String esperado) {
        assertThat(subs.resolver(nombre, TV)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("punctuation glued to the leading noun, and doubled blanks, do not hide it")
    @CsvSource(delimiter = '|', value = {
            "Parlante/Bluetooth Gadnic 20W|Parlante|" + TV,
            "Parlante:Portatil Gadnic|Parlante|" + TV,
            "Smart(TV) Samsung 55\"|Televisor|" + TV,
            "Cafetera/Molinillo Oster|Cafetera|" + PE,
            "Licuadora,Vaso De Vidrio Oster|Licuadora y Exprimidor|" + PE,
            "  Máquina  de  Cortar  Pelo  Wahl|Afeitadora y Cortadora|" + PE,
            "SECADOR DE PELO REVLON|Secador de Pelo|" + PE,
    })
    void puntuacion(String nombre, String esperado, String categoria) {
        assertThat(subs.resolver(nombre, categoria)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("a name no rule claims has no sub, and no sport sub leaks in")
    void sinSub() {
        assertThat(subs.resolver("Máquina de coser Singer M1605", PE)).isEmpty();
        assertThat(subs.resolver("Yogurtera Atma YMAT26", PE)).isEmpty();
        assertThat(subs.resolver("Roku Express 4K Dispositivo de streaming", TV)).isEmpty();
        // tier 2 would read "running" / "tenis" here; neither category takes a sport sub
        assertThat(subs.resolver("Balanza Running Tenis Pro", PE)).isEmpty();
        assertThat(subs.resolver("Auriculares Running Tenis", TV)).isEmpty();
    }

    @Test
    @DisplayName("blank and unknown input stay empty")
    void vacio() {
        assertThat(subs.resolver("", PE)).isEmpty();
        assertThat(subs.resolver(null, TV)).isEmpty();
        assertThat(subs.resolver("Cafetera", "Otros")).isEmpty();
    }
}
