package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Every name comes from the catalogue. The classifier decides Networking, NetworkingReglas its sub. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Networking — category and sub-category over real names")
class NetworkingClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1} / {2}")
    @CsvSource(delimiter = '|', value = {
        "Router Wireless Mercusys 300mbps N 3 Antenas                                              | Networking | Router",
        "Router Tp-Link Archer AX55 AX3000 Dual Band WIFI 6 One Mesh Ultra Low Latency             | Networking | Router",
        "Router Tp-Link EX520 AX3000 Dual Band WiFi 6 Easy Mesh Mu-Mimo Low Latency                | Networking | Router",
        "Router XPON Wifi6 ax3000 doble banda -fibra óptica -4 antenas 6dbi y 4 puertos gigalan    | Networking | Router",
        "Router Mesh Tp-Link Deco E4 AC1200 WIFI 5 Dual Band Low Latency                           | Networking | Mesh",
        "Access Point Mesh WIFI Tp-Link Deco S7 Pack 2 Unidades AC1900                             | Networking | Mesh",
        "DECO X20 PACK DE 2 MESH TP LINK AX1800 WIFI GIGABIT                                       | Networking | Mesh",
        "MESH TP-LINK DECO X50-POE PACK DE 3 AX3000 WIFI6                                          | Networking | Mesh",
        "Acces Point TP- Link Deco E4 Pack de 2 Mesh AC1200 Wifi System                            | Networking | Mesh",
        "ACCESS POINT HP AP-505 AX                                                                 | Networking | Access Point",
        "ACCESS POINT TP-LINK EAP620 HD AX1800 WIFI6 MONTAJE TECHO                                 | Networking | Access Point",
        "ROUTER TP-LINK TL-WA801N AP WIRELESS 300MBPS 2A (2461)                                    | Networking | Access Point",
        "Switch TP-Link DS108G 8 Puertos Gigabit Omada                                             | Networking | Switch",
        "Switch Cisco C1300-24P-4X 24 Puertos PoE+ + 4 SFP+ 10G                                    | Networking | Switch",
        "Extensor WiFi TP-Link RE200 AC750 Dual Band                                               | Networking | Repetidor",
        "Extensor de Red Tp-Link RE505X AX1500 WIFI 6 Dual Band EasyMesh PuertoGigabit             | Networking | Repetidor",
        "Repetidor WIFI Extensor de Señal Mercusys MW300RE 2.4Ghz 300Mbps                          | Networking | Repetidor",
        "Router Repetidor Asus N300 2 Antenas RT-N300                                              | Networking | Repetidor",
        "Placa De Red Pci-E Tp-Link Tg-3468 Gigabit 10/100/1000                                    | Networking | Placa y Adaptador",
        "Placa WIFI USB Mercusys MU6H 200Mbps                                                      | Networking | Placa y Adaptador",
        "Adaptador Bluetooth TP-LINK UB500 USB Nano Bluetooth 5.3                                  | Networking | Placa y Adaptador",
        "Adaptador USB WIFI Tp-Link Archer T2U AC600 Dual Band 2.4 - 5Ghz 600Mbps                  | Networking | Placa y Adaptador",
        "ADAPTADOR TP-LINK UE300C USB-C A ETHERNET GIGABIT RJ45                                    | Networking | Placa y Adaptador",
        "Adaptador de Thunderbolt a Gigabit Ethernet de Apple                                      | Networking | Placa y Adaptador",
        "Adaptador Usb C Rj 45 5000 Mbps Dehuka                                                    | Networking | Placa y Adaptador",
        "Cable De Red Cat6 Patch Cord Utp Flat 8 M Rj45 - Vention                                  | Networking | Cable de Red",
        "Cable UTP armado Cat 6 25m NISUTA - NSCUT625                                              | Networking | Cable de Red",
        "Cable de red SFTP Cat8 40Gbps de 10m NISUTA - NSCUT810                                    | Networking | Cable de Red",
        "Cable Patchcord Cat 5e 10 Mts                                                             | Networking | Cable de Red",
        "CABLE DE RED NOGA PATCH CORD 5M CAT6                                                      | Networking | Cable de Red",
        "Cable De Red 15 Metros Cat.5e Rj45 Smart Tv Modem Router Ps4                              | Networking | Cable de Red",
        "Ficha de Cable Red RJ 45                                                                  | Networking | Conector",
        "Ficha Rj45 Plastica Reforzada Con Metal                                                   | Networking | Conector",
    })
    @DisplayName("a network name is Networking, with the sub its first matching rule gives")
    void networkingYSuSub(String nombre, String categoria, String sub) {
        String n = nombre.trim();
        assertThat(cat(n)).isEqualTo(categoria);
        assertThat(subs.resolver(n, categoria)).isEqualTo(sub.trim());
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Cable HDMI-HDMI M-M Comun 1.5M                                                            | Cable",
        "Adaptador Display Port M a HDMI H                                                         | Cable",
        "Cable USB a Micro USB 2A 1m Strong Series Negro                                           | Cable",
        "Adaptador HUB 8 en 1 Dellorean AH8X USB-C MacBook HDMI 4K Ethernet Gigabit RJ45 SD TF PD 87W | Cable",
        "Adaptador 6-en-1 CASA Hub A01 de USB-C a HDMI/Ethernet/2 USB-A/USB-C/Lector de tarjeta SD de Adam Elements - Gris espacial | Cable",
        "Adaptador Hub USB HIKSEMI DS11 USB-C A USBX4 HDMIX1 RJ45X1 VGAX1                          | Cable",
        "Teclado Mecánico Raptor Fireclaw M87 Red Red Switch Español Blanco                        | Teclado",
        "Nintendo Switch - OLED Model: Mario Red Edition                                           | Consola",
        "Mouse Logitech M110 Silent Red                                                            | Mouse",
        "Silla de oficina Quantic OC-2207 Mesh negra                                               | Silla",
        "Gabinete Sentey M12 Coyote Mesh TG C/Coolers x3 ARGB Vidrio Templado White               | Gabinete",
        "Remera mesh Varsity                                                                       | Remera",
    })
    @DisplayName("what names a switch, a mesh or an Ethernet port without being network gear keeps its category")
    void loQueNoEsRed(String nombre, String categoria) {
        assertThat(cat(nombre.trim())).isEqualTo(categoria);
    }

    @Test
    @DisplayName("Cable has no red sub any more: a cable that names a port is Networking or a plain Cable")
    void cableNoTieneSubRed() {
        assertThat(subs.resolver("Cable Auxiliar Jack 3.5 mm", "Cable")).isEmpty();
        assertThat(subs.resolver("Cable HDMI 2.0 4K 3 metros", "Cable")).isEqualTo("video");
        assertThat(subs.resolver("Cable USB 3.0 A-B 1.5 m", "Cable")).isEqualTo("usb");
    }

    @Test
    @DisplayName("the vocabulary is closed: Networking is in the canon and Red is not")
    void vocabularioCerrado() {
        assertThat(CategoryGroups.canonicalCategories()).contains(NETWORKING).doesNotContain("Red");
        assertThat(CategoryGroups.esCategoriaTech(NETWORKING)).isTrue();
        for (String[] regla : NetworkingReglas.SUBS) {
            assertThat(regla[0]).as("sub labels are Title Case chips").matches("[A-Z][A-Za-z ]+");
        }
    }

    private static final String NETWORKING = "Networking";

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Monitor Gamer AsRock Phantom PG32QF2B 31.5\" VA QHD 1440p 165Hz FreeSync Antena WiFi Integrada",
        "HUB USB A 4 PUERTOS NETMAK NM-AC08 USB 2.0 CON SWITCH Y LUZ LED INDIVIDUAL",
    })
    @DisplayName("a monitor with a Wi-Fi antenna or a USB hub with a switch is not Networking")
    void monitorAndHubAreNotNetworking(String nombre) {
        assertThat(new CategoryClassifier().normalizarCategoria("", nombre)).isNotEqualTo("Networking");
    }
}
