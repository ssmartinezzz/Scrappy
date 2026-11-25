package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Names are real rows that were in Otros on the dev catalog (2026-11-25), from Dellorean, Gadnic, Netmak, Adam Elements, Xiaomi and others. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("AccesorioDeTelefonoClassifier — only what would be Otros: phone chargers, power banks, holders and glass, and the families that look alike")
class AccesorioDeTelefonoClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Cargador de Pared Samsung Ep-T2510nbsgar",
        "Cargador de Pared Motorola Mc-336L",
        "Cargador Motorola de 125W",
        "Cargador USB-C de 20 W de Apple",
        "Apple Power Adapter Cargador de 12W con Puerto USB",
        "Cargador Samsung 25W USB tipo C Negro con cable",
        "Cargador Pared 2 Puertos USB TGW",
        "Cargador De Pared USB-C 25W Netmak NM-CP5 Carga Rapida",
        "Cargador de Pared +  Cable Dekkin At-Acc-Ki-042",
        "Cargador Carga Rapida Microcase 20w Usb/ Usb Tipo C - Negro",
        "Cargador USB para Auto 2 Puertos Datrak",
        "CARGADOR PARA AUTO USB - C CARGA RAPIDA 12V 3A CAR USB 21",
        "Cargador de coche Aukey Dual de 65 W y 2 puertos",
        "Cargador para auto doble (USB-C / USB-A) OMNIA C1 Pro de 83 W de Adam Elements - Negro",
        "Cargador inalambrico Qi -15W cable USB type-C blanco Netmak NM-BC16-W",
        "Cargador Inalambrico Pure Gear Qi - 63491Pg",
        "Cargador Inalambrico Magnetico 3 en 1 MC2 Qi2",
        "Cargador Wireless 3 en 1",
        "Cargador magnético inalámbrico para auto OMNIA C2 de 15 W de Adam Elements - Negro",
        "Mini Cargador 2 En 1 Inalámbrico Rápido Para Iwatch Y iPhone, Carga Magnética Dehuka",
        "CARGADOR NOGANET MICRO USB 5V 2A NGA-520",
    })
    @DisplayName("a phone charger (wall, car, wireless, magnetic, USB-C, brand) is Celular")
    void chargers(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Celular");
        assertThat(subs.resolver(nombre, "Celular")).isEqualTo("Cargador");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Power Bank Xiaomi 33W 20000 Mah",
        "Powerbank Trust Primo 15000 Mah Carga Rapida 15w Usb C Color Negro",
        "Batería externa magnética VIONTA L10 Ultradelgada de 10.000 mAh de Adam Elements - Plata",
        "Batería externa plegable (USB-C) GRAVITY F5C de 5.000 mAh de Adam Elements - Rosa",
        "Cargador Portatil Xiaomi Power Bank 20000 mAh 22.5 W Cable Integrado Gris Claro",
        "Cargador Portátil Gadnic Batería 25000 mAh",
        "Cargador Gadnic 5000Mah Carga Rápida USB C Power Bank",
        "Cargador inalámbrico magnético Miccell VQ-P31S 10000Mah",
        "Kit Gadnic de Cargador Portátil y Cable Usb Magnético Carga Rápida",
        "Belkin Portable Charger, 3-Port Power Bank - 12in. USB-C to USB-C Cable Included - 20000 mAh w/PD 20W for iPhone 17, Air, Pro",
        "Mini Power Bank 22.5W 5000mAh USB-C Plegable Ringo PWBN-C61-PI01",
    })
    @DisplayName("a power bank or external battery is Celular, sub Batería Externa")
    void powerBanks(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Celular");
        assertThat(subs.resolver(nombre, "Celular")).isEqualTo("Batería Externa");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Soporte Portacelular Flexible Succ|Soporte",
        "Soporte Magnético para Celular Dehuka Car Back Seat Hook 360º Regulable|Soporte",
        "Soporte de Celular Magnético Plegable Universal Multisuperficie Blanco|Soporte",
        "Vidrio Templado para Samsung Galaxy A15 9H|Protector",
        "Protector de pantalla Hidrogel para Motorola Moto G54|Protector",
    })
    @DisplayName("a phone holder or phone glass the name ties to a phone is Celular")
    void holdersAndGlass(String nombre, String sub) {
        assertThat(cat(nombre)).isEqualTo("Celular");
        assertThat(subs.resolver(nombre, "Celular")).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Cargador Arrancador De Batería Auto 100 Amp",
        "Cargador Mantenedor Bateria 12v Inteligente Lusqtoff Lct2000",
        "Cargador Bateria Herramientas 40v Inalambricas Ecoline",
        "Cargador 42v 2.0 Ah Rapido De Bateria Robusto 220v",
        "Cargador De Pared 21.5v 0.6 A Worksite",
        "Cargador Secuencial Baterias Dual M18 Y M12 220v Milwaukee",
        "Kit Batería 4 Ah y Cargador 3 A Ion de Litio PXC Plus Einhell",
        "Cargador Daihatsu Hica2024 21.5v 2400ma Negro 65w",
        "Cargador Homologado Gadnic 12V 1A",
        "Cargador y arrancador inteligente profesional Daihatsu 12V / 24V - Modelo CIA80",
    })
    @DisplayName("tool, car and bike battery chargers and jump starters abstain")
    void toolAndVehicleChargers(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Celular");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Cargador De Pilas -p/4 Pilas AA - AAA -2x Pilas 9v De Canal Individual -NiNh/NiCd Probattery Pro1 FRLI-432-CP1",
        "Cargador USB Etheos CGPLUSB4 para pilas recargables AA y AAA - Blanco",
        "Cargador + 2 Pilas Noganet Nga-5100",
        "Power Bank Jump Starter Gadnic 1000mah",
        "Cargador Para Forerunner 45 55 165 255 265 955 Fenix 5 6 7 8 Vivoactive 3 4 4s",
        "Cargador Para W12 W26 Kw66 Ls05 Sw022 Solar Ls05 Moto Watch 100 Kieslect L11 K10",
        "Cargador magnético inalámbrico de carga rápida OMNIA A1 + para Apple Watch de Adam Elements - Blanco",
        "Cargador universal p/notebooks -90w -8 conectores -Probattery",
        "Cargador Pared Dellorean 140W Ultra Rápido 4 Salidas USB-C USB Macbook iPhone Notebook",
        "Cargador Portátil Dellorean PowerOn Travel 15000mAh 65W 4 en 1 Magsafe Enchufe Rebatible Carga Notebook USB-C",
        "Cargador USB-C de 96 W de Apple",
        "Baterías Recargables Akaso 1350 mAh Cargador Doble USB Compatible Cámara Brave 7 LE",
        "Hub Cargador de Baterías para Drone DJI Tello",
        "Batería Powerbank Portátil Para Starlink Mini Gadnic Hasta 5Hs",
        "Cargador 65W + Hub",
        "Farol Linterna Dellorean LTDO1/1 Trípode Telescópico 97cm 450 Lúmenes IPX5 Power Bank Batería 10000mAh",
        "Cortadora Pelo Profesional Dellorean HoverCut Motor Magnético 11500 RPM Autonomía 150min Carga Rápida USB-C Pantalla Digi",
        "Dehuka Cable Cargador De Carga Usb Para iPhone",
        "AC Power Cord - Rapid Charging USB Type C - Cargador compatible con el DOCK y modo portàtil",
    })
    @DisplayName("AA/AAA, jump-starter, watch, notebook, camera, drone, Starlink, hub, lantern, clipper and cable-led names abstain")
    void otherDevices(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Celular");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Film Protector Amazon Kindle Paperwhite 4 - 10ma Generacion",
        "Film Espejado para Vidrios - Privacidad y Control Térmico",
        "Film Cobertor Plástico Rapifix Para Enmascarar Autos 4 x 100 Mts",
        "Resma A4 Papel Traslucido Film Adhesivo X 20 Hojas 80Gr NewAkira Inkjet PET",
        "Papel de Pelicula FUJIFILM INSTAX FILM MINI RAINBOW (10/PK)",
        "Balanza De Cocina Gadnic Digital Vidrio Templado 5Kg",
        "Balanza Digital GA.MA Vidrio Templado SCG 430 Negro",
        "Soporte Tablet/Celular 16.5X25.5X16.5 Cm Madera Marrón Productos Industriales del Sur",
        "Exquisite Gaming: Rare: Banjo-Kazooie - Original Mobile Phone & Gaming Controller Holder, Device Stand, Cable Guys, Licensed",
        "Kirby - Pen Stand Pen Holder - Rotating Free Stand Doodle",
        "Apple Adaptador USB-C a USB",
        "Adaptador de pantalla inalámbrico Verbatim USB-C",
        "Cámara Instantánea Fujifilm Instax Mini 12 Lila + 10 Fotos",
    })
    @DisplayName("films, glass kitchenware, wooden stands, controller holders, adapters and cameras stay out")
    void sameNounOtherThing(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Celular");
    }

    @Test
    @DisplayName("it runs last: a name another family claims keeps that category")
    void otherFamiliesWin() {
        assertThat(cat("Cargador de Baterías 12V GAL 12V-20 Bosch")).isNotEqualTo("Celular");
        assertThat(cat("Cable USB-C a USB-C Carga rápida PD 60W Miccell")).isEqualTo("Cable");
    }

    @ParameterizedTest(name = "[{index}] {0} on leaf {1}")
    @CsvSource({
        "CINTA CAMINADORA NOGA SOPORTE PARA CELULAR, Equipamiento Deportivo",
        "Cargador Para Auto Havit Hv-Cc813, Accesorio de Auto",
        "Luz de emergencia recargable con power bank 5200 mAh, Artículo de Camping",
    })
    @DisplayName("the curated leaf of the site outranks it")
    void curatedLeafWins(String nombre, String hoja) {
        assertThat(classifier.normalizarCategoria("", nombre, hoja)).isEqualTo(hoja);
    }

    @Test
    @DisplayName("a portable 3-in-1 wireless charger has no battery: it stays Cargador")
    void portableStandIsNotAPowerBank() {
        assertThat(subs.resolver("Cargador Magnético 3en1 Portátil 20W Gadnic BASEU051", "Celular")).isEqualTo("Cargador");
        assertThat(subs.resolver("Cargador MagSafe 3-en-1 Portátil", "Celular")).isEqualTo("Cargador");
    }
}
