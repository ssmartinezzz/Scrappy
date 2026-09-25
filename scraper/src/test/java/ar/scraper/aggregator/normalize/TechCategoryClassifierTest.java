package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `richer-category-taxonomy` — las quince categorías nuevas, y el ORDEN que las
 * hace funcionar.
 *
 * <p>{@code Otros} tenía 2.974 de las 16.830 filas activas — 14% del catálogo.
 * Adentro había 453 teclados, 302 mouses, 285 fuentes, 231 discos y 161
 * productos de red. No estaban mal clasificados: ningún keyword los nombraba.
 * {@code KW_TECLADO} no tenía la palabra "teclado" pelada, sólo "teclado
 * gamer"/"teclado mecanico", así que un "Teclado Logitech K120 USB" no
 * matcheaba nada.</p>
 *
 * <p><b>Todos los nombres de abajo salieron de la base.</b> Los casos de
 * {@link #elOrdenDelBloqueTechEsLoadBearing()} son los que importan de verdad:
 * cada uno es un producto que contiene DOS sustantivos de categoría y donde
 * sólo uno describe lo que el producto ES.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("tech and sports-equipment categories")
@DisplayName("CategoryClassifier — categorías de tecnología y equipamiento deportivo")
class TechCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        // El sustantivo pelado que faltaba — 453 y 302 filas en Otros
        "'Teclado Logitech K120 USB Español Negro',                    Teclado",
        "'TECLADO BLUETOOTH LOGITECH K250 GRAFITO',                    Teclado",
        "'Mouse Logitech M90 Black USB',                               Mouse",
        "'Mouse Philips M234 Usb 1000dpi Black',                       Mouse",
        // Fuente / Motherboard / Almacenamiento
        "'Fuente 550w LNZ XT550-SS - ATX',                             Fuente",
        "'Fuente Jalatec Jt-520',                                      Fuente",
        "'Motherboard Asus Prime B550M-K AM4',                         Motherboard",
        "'Disco Solido SSD 240GB Kingston A400 SATA III',              Almacenamiento",
        "'HD SSD 120GB HIKSEMI WAVE SATA III 2.5\"',                   Almacenamiento",
        "'Pendrive 64Gb Kingston DT70 Usb Tipo C',                     Almacenamiento",
        // Red / Cable / Impresión
        "'Router Wireless Mercusys 300mbps N 3 Antenas',               Red",
        "'Placa De Red Wifi Usb Archer T2u Ac600 Dual Band',           Red",
        "'Switch Tenda SG108M 8 Puertos Gigabit',                      Red",
        "'Cable HDMI-HDMI M-M Comun 1.5M',                             Cable",
        "'Adaptador Display Port M a HDMI H',                          Cable",
        "'Cable de Red RJ-45 2M',                                      Cable",
        "'Impresora Laser Pantum BP2300W Wifi',                        Impresión",
        "'Toner Alternativo Brother TN1060',                           Impresión",
        // El resto
        "'Mouse Pad Fantech MP64 Basic 640x210x2mm Black',             Mousepad",
        "'Joystick Redragon Saturn Pc USB G807',                       Joystick",
        "'Volante Logitech G29 PS5 PC Con Pedalera',                   Joystick",
        "'Microfono Fantech MCX03 Leviosa Max RGB Black USB Type C',   Micrófono",
        "'UPS Hunnox 650VA',                                           UPS",
        "'Camara Ip Cloud Tp-Link Tapo C201 Black',                    Cámara",
        "'Reloj Smartwatch Xiaomi MI Smart Band 9 Active Black',       Reloj",
        // Equipamiento deportivo
        "'Pelota De Voley DRB Classic 1.0 N°5',                        Pelota",
        "'Pelota adidas Club Uefa Champions League 26/27 Unisex',      Pelota",
        "'Paleta De Pádel Babolat Counter Vertuo 2.6',                 Paleta",
        "'Paleta De Ping Pong Rebook 5 Star',                          Paleta",
    })
    @DisplayName("Los sustantivos que vivían en Otros ahora tienen categoría")
    void losSustantivosQueVivianEnOtrosAhoraTienenCategoria(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
        "'Mini Pc Cx Amd Ryzen 7 6800H 16Gb 480Gb Free',                        Mini PC",
        "'MINI PC GIGABYTE BRIX CORE I5 10210U S/MEMO S/DISCO',                 Mini PC",
        "'Mini PC ASUS PN52-BB7000XTC Ryzen 7 5800H',                          Mini PC",
        "'MINI PC ASUS ULTRA 5 225H NUC15CRK BAREBONE S/MEMO S/DISCO',         Mini PC",
        "'MINI PC MSI CUBI 5 12M I3-1215U BAREBONE S/MEMO S/DISCO',            Mini PC",
        "'Mini Pc Jalatec Jt-mpr3 Pc Ryzen 3 Amd 3250c+ 8gb + 256gb',          Mini PC",
    })
    @DisplayName("Mini PC sale del slot cpu: el sustantivo líder gana antes que CPU/Monitor")
    void miniPcSaleDelSlotCpu(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Un mini PC combinado con un monitor sigue siendo un mini PC — el contenedor gana")
    void miniPcConMonitorCombinadoSigueSiendoMiniPc() {
        assertThat(cat("Mini PC CX AMD Ryzen 7 6800H 16GB 480GB + Monitor 22\" + Kit Tec/Mouse"))
                .isEqualTo("Mini PC");
        assertThat(cat("Mini Pc Asus Ryzen 5 5500U 16GB 512GB + Monitor 24\""))
                .isEqualTo("Mini PC");
    }

    @Test
    @DisplayName("Un servicio de armado con \"brix\" en el nombre no es un mini PC, y un kit de gabinete tampoco")
    void miniPcNoSeComeElServicioNiElKitDeGabinete() {
        // El guard de servicio corre ANTES que el líder de Mini PC.
        assertThat(cat("ARMADO DE BRIX/NOTEBOOK Y AFINES")).isEqualTo("Otros");
        // "Barebone" no es un keyword de Mini PC — el líder es "Kit", y el sustantivo sigue siendo Gabinete.
        assertThat(cat("Kit Gabinete Gamer Jalatec Barebone Jt-k80 Rgb C/Fuente 500w"))
                .isEqualTo("Gabinete");
    }

    @Test
    @DisplayName("El orden del bloque tech es load-bearing: el contenedor gana sobre lo que contiene")
    void elOrdenDelBloqueTechEsLoadBearing() {
        // Gabinete ANTES que Fuente: 23 gabinetes activos vienen con fuente incluida
        // Esperaba "Conjunto" hasta que el set de evaluación mostró que era un bug.
        assertThat(cat("Gabinete Gamer Kit c/Fuente 500W")).isEqualTo("Gabinete");
        assertThat(cat("Gabinete Sentey c/Fuente 500W ATX")).isEqualTo("Gabinete");

        // Gabinete ANTES que Cooler: 268 gabinetes nombran sus fans
        assertThat(cat("GABINETE COOLERMASTER ELITE 301 BLACK 3FAN ARGB")).isEqualTo("Gabinete");
        assertThat(cat("Gabinete Cooler Master Elite 302 C/Coolers x3 White")).isEqualTo("Gabinete");

        // Fuente ANTES que Cooler: 27 fuentes nombran el suyo
        assertThat(cat("Fuente Magnum Tech 600W Cooler 120mm MT-PSU600")).isEqualTo("Fuente");
        assertThat(cat("Fuente Cooler Master 650W 80 Plus Gold MWE V3 ATX 3.1")).isEqualTo("Fuente");

        // Cámara ANTES que Monitor: el producto termina en la palabra monitor
        assertThat(cat("Camara Wifi Ezviz BM1 2mp Baby Call Monitor")).isEqualTo("Cámara");

        // Mousepad ANTES que Mouse
        assertThat(cat("Mouse Pad Venex")).isEqualTo("Mousepad");

        // Notebook ANTES que RAM y Almacenamiento: la notebook los declara
        assertThat(cat("Notebook Lenovo IdeaPad 3 8GB RAM 512GB SSD")).isEqualTo("Notebook");
    }

    @Test
    @DisplayName("Un switch de teclado mecánico no es un switch de red")
    void switchDeTecladoNoEsSwitchDeRed() {
        // "Red" es un COLOR acá, y "Switch" el tipo de switch mecánico.
        assertThat(cat("Teclado Mecánico Raptor Fireclaw M87 Red Red Switch Español Blanco"))
                .isEqualTo("Teclado");
        assertThat(cat("TECLADO MECANICO RAPTOR FIRECLAW M87 RETROILUMINADO SWITCH RED OUTEMU"))
                .isEqualTo("Teclado");
        // ...y ni siquiera una remera
        assertThat(cat("Remera Boxy ZX Switch Crudo")).isEqualTo("Remera");
        // El switch de red de verdad, que nombra puertos o velocidad, sí entra
        assertThat(cat("Switch 5p Tp-Link TL-SG1005D Gigabit 10/100/1000")).isEqualTo("Red");
    }

    @Test
    @DisplayName("El color rojo ya no deja un mouse sin clasificar")
    void elColorRojoNoDejaUnMouseSinClasificar() {
        // NonTextileGuard tenía "red " (de red deportiva) y mira los primeros 35
        // caracteres: un mouse rojo entraba entero en esa ventana y salía sin
        // categoría. En el catálogo no hay una sola red deportiva.
        assertThat(cat("Mouse Logitech M110 Silent Red")).isEqualTo("Mouse");
        assertThat(cat("Mouse Logitech M280 Wireless Red")).isEqualTo("Mouse");
    }

    @Test
    @DisplayName("'Fan' quiere decir hincha en el catálogo de indumentaria")
    void fanQuiereDecirHinchaEnIndumentaria() {
        // Por esto " fan " pelado NO está en KW_COOLER.
        assertThat(cat("Remera Fiume Sport Linea Fan Godoy Cruz")).isEqualTo("Remera");
        assertThat(cat("Short Le Coq Sportif Pumas Titular Fan 2025 De Hombre")).isEqualTo("Short");
    }

    @Test
    @DisplayName("Un ratón de Disney no es un periférico, y un mousse no es un mouse")
    void unRatonDeDisneyNoEsUnPeriferico() {
        // El bloque TECH corre antes que el de ropa: sin veto, estos tres
        // entraban al catálogo como mouse. Son productos reales.
        assertThat(cat("Zapatillas Footy Mickey Mouse")).isEqualTo("Zapatilla");
        assertThat(cat("Mochila Adidas Disney Minnie Mouse")).isEqualTo("Mochila");
        // El producto dice GAINER y ahora se clasifica como tal: KW_GAINERS sólo
        // tenía "mass gainer", así que caía en Proteína. Lo que este test fija
        // sigue intacto — el mousse de chocolate no es un mouse.
        assertThat(cat("DULKRE SPORT GAINER WHEY PROTEIN MOUSE DE CHOCOLATE 1.5KG"))
                .isEqualTo("Gainer");
        // ...y el periférico de verdad sigue siendo Mouse
        assertThat(cat("Mouse Logitech M90 Black USB")).isEqualTo("Mouse");
    }

    @Test
    @DisplayName("'Core I' no siempre es un Core i5")
    void coreINoSiempreEsUnProcesador() {
        // "core i" abierto se comía "Cloud Stinger Core Inalámbrico" (un
        // auricular) y "Master Liquid 360 Core II" (un water cooler).
        assertThat(cat("Auricular HyperX Cloud Stinger Core Inalámbrico White PC - PS4 - PS5"))
                .isEqualTo("Auricular");
        assertThat(cat("Procesador Intel Core i5 12400F")).isEqualTo("CPU");
    }

    @Test
    @DisplayName("Un cable se reconoce por sustantivo LÍDER, no por aparición")
    void elCableSeReconocePorSustantivoLider() {
        // Una fuente que publicita el largo de sus cables sigue siendo una fuente
        assertThat(cat("Fuente Segotep 500W ATX Cables Largos 23a Cooler 120mm Negro"))
                .isEqualTo("Fuente");
        // ...y el cable que arranca diciendo que lo es, es un cable
        assertThat(cat("Cable Splitter PWM Mallado para Fan Cooler Zer01 Gaming 1 x 4"))
                .isEqualTo("Cable");
    }

    @Test
    @DisplayName("Gabinete es gabinete — el líder decide, no 'para gabinete'")
    void gabineteEsGabinete() {
        assertThat(cat("Service instalación de armado de PC o cambio de gabinete")).isEqualTo("Otros");
        assertThat(cat("Bracket disco SSD para gabinete Xigmatek Medusa")).isEqualTo("Otros");
        assertThat(cat("Filtro antipolvo Xigmatek magnetico para gabinete 12x120mm")).isEqualTo("Otros");
        assertThat(cat("Fuente 600W mini gabinete slim")).isEqualTo("Fuente");
        assertThat(cat("PC Armada AMD Ryzen 5 8500G+A620+32GB+1TB NVMe+Gabinete Gamer")).isEqualTo("PC");
        assertThat(cat("PC AMD Ryzen 7 8700G+A620+16GB+1TB M.2 NVMe+Gabinete Gamer")).isEqualTo("PC");

        assertThat(cat("Gabinete Sentey H30 TG Vidrio Templado")).isEqualTo("Gabinete");
        assertThat(cat("Gabinete Magnum Tech MT-K835 con Fuente 500W")).isEqualTo("Gabinete");
        assertThat(cat("Evolabs Luma X EVO-320AB - Gabinete Gaming con 4 Ventiladores ARGB")).isEqualTo("Gabinete");
        assertThat(cat("Outlet - Gabinete Gamer Zer01 Gaming Gemini 1 Fan Fixed RGB")).isEqualTo("Gabinete");
    }

    @Test
    @DisplayName("Un bracket es un bracket aunque no diga 'para gabinete'")
    void unBracketNoEsElComponenteAlQueSeAtornilla() {
        // El guard de la fase 7 exigía " para gabinete " en el mismo título, así
        // que estos tres se escapaban y ganaban su slot por ser lo más barato
        // del pool: el de Almacenamiento a $3.300 y el de Cooler.
        assertThat(cat("Bracket Disco SSD para Xigmatek Gaming X")).isEqualTo("Otros");
        assertThat(cat("Bracket Cooler Master Soporte Para Fan Cooler LGA1700")).isEqualTo("Otros");
        assertThat(cat("Bracket Disco SSD para Gabinete Xigmatek Medusa")).isEqualTo("Otros");
    }

    @Test
    @DisplayName("'Armado de PC' es el SERVICIO de armado, no una PC armada")
    void armadoLiderEsUnServicio() {
        // Las 10 filas que lideran con "armado" son mano de obra, no producto —
        // ocho ya vivían en Otros y dos se habían ido a GPU, donde competían
        // por el slot gpu del armador ("ARMADO ITEM 6302" a $800).
        assertThat(cat("ARMADO ITEM 6302 GTX 1050 Ti ATHLON 950 8GB")).isEqualTo("Otros");
        assertThat(cat("ARMADO DE PC PROMO GTX 1060")).isEqualTo("Otros");
        assertThat(cat("ARMADO DE BRIX/NOTEBOOK Y AFINES")).isEqualTo("Otros");
        assertThat(cat("ARMADO BASICO DE PC (No incluye instalación de sistema operativo)")).isEqualTo("Otros");

        // Y una PC armada de verdad, que NO lidera con "armado", sigue siendo PC.
        assertThat(cat("PC Armada AMD Ryzen 5 8500G+A620+32GB+1TB NVMe+Gabinete Gamer")).isEqualTo("PC");
    }

    @Test
    @DisplayName("Un procesador que nombra su cooler como accesorio sigue siendo CPU")
    void unProcesadorQueNombraSuCoolerSigueSiendoCpu() {
        // KW_COOLER corre antes que KW_CPU (el cooler de un CPU no es un CPU),
        // así que un procesador que menciona "cooler" como accesorio caía acá.
        // 146 de 470 filas de Cooler eran CPUs (pc-builder-deep-taxonomy, T2a).
        assertThat(cat("Procesador AMD Ryzen 9 9950X3D 16/32 5.6GHz AM5 (no incluye cooler)"))
                .isEqualTo("CPU");
        assertThat(cat("Procesador AMD Ryzen 5 8500G 5.0GHz Turbo AM5 + Wraith Stealth Cooler"))
                .isEqualTo("CPU");
        // "Outlet" al frente es una etiqueta de venta, no el sustantivo — mismo
        // trato que el líder de Gabinete/Fuente/PC.
        assertThat(cat("Outlet Procesador Intel Core i5 13600KF S/Cooler S/Video LGA1700"))
                .isEqualTo("CPU");
        assertThat(cat("Micro AMD Ryzen 7 5700X 4.6 GHz AM4 Tray Sin Cooler"))
                .isEqualTo("CPU");

        // Un cooler sigue siendo un cooler cuando ES el producto
        assertThat(cat("CPU Cooler Cooler Master DT621 R1")).isEqualTo("Cooler");
        assertThat(cat("Cooler para CPU Intel/AMD Deepcool AG400")).isEqualTo("Cooler");
        assertThat(cat("CPU Water Cooler Lovingcool 240mm AK-B240-03")).isEqualTo("Cooler");

        // Micro SD no es un procesador: bare " micro " no puede entrar a la
        // lista de líderes.
        assertThat(cat("Tarjeta de Memoria Micro SD Kingston 64GB")).isEqualTo("Almacenamiento");
    }

    @Test
    @DisplayName("'Patinaje Dc Shoes' son zapatillas de skate, no patines")
    void patinajeDcShoesSonZapatillasDeSkate() {
        assertThat(cat("Patinaje Dc Shoes Slathletic Heritage Hombre Blancas ZXUK-9458"))
                .isEqualTo("Zapatilla Skate");
        // ...pero el mismo prefijo en una gorra o un pantalón NO los vuelve calzado:
        // KW_SKATE_GENERICO exige esZapatilla, y el fallback de calzado corre último.
        assertThat(cat("Patinaje Dc Shoes University Cap Snapback Hombre Azules")).isEqualTo("Gorra");
        assertThat(cat("Patinaje Dc Shoes Label Beanie Hombre Beige")).isEqualTo("Gorro");
        assertThat(cat("Patinaje Dc Shoes Worker Baggy Carpenter Ril Vaqueros Hombre Indigo"))
                .isEqualTo("Baggy");
    }

    @Test
    @DisplayName("PC/CPU por sustantivo líder ganan sobre cualquier otro sustantivo del título (T4d-1)")
    void pcYCpuLiderCorrenAntesQueTodoElBloqueTech() {
        // Una PC armada entera que nombra su GPU en el título no es la placa
        // de video suelta — KW_GPU ("rtx 5060") corría antes que KW_PC_LIDER
        // y se la quedaba (pc-builder-deep-taxonomy, T4 build (2)).
        assertThat(cat("PC Powered by MSI Ultimate AMD Ryzen 7 5700X B550 32GB RAM 1TB RTX 5060 750W Gold Cpu Cooler WIFI"))
                .isEqualTo("PC");
        // Los dos ya cubiertos por T1/T2 siguen intactos con el líder al tope.
        assertThat(cat("Procesador Intel Core i5 12400F 4.4GHz Turbo Socket 1700 Alder Lake"))
                .isEqualTo("CPU");
        assertThat(cat("Placa de Video MSI NVIDIA GeForce RTX 5070 Ventus 2X 12GB OC GDDR7"))
                .isEqualTo("GPU");
    }

    @Test
    @DisplayName("T14: un pad/pasta térmica para CPU no es un CPU — KW_COOLER lo cubre antes de llegar a \" cpu \"")
    void padTermicoParaCpuNoEsUnCpu() {
        // Medido, pc-builder-homelab T14: "para cpu" matcheaba KW_CPU (" cpu
        // ") porque KW_COOLER no tenía ninguna forma de "thermal pad" — sólo
        // "pasta termica"/"grasa termica", que este nombre no usa.
        assertThat(cat("Thermal Pad Carbice Ice Pad para CPU AM4/AM5 con Nanotubos de Carbono"))
                .isEqualTo("Cooler");
        assertThat(cat("Pad Termico Cooler Master para CPU 1mm")).isEqualTo("Cooler");
        assertThat(cat("Thermal Paste Cooler Master MasterGel Pro V2")).isEqualTo("Cooler");
        // Las dos formas ya cubiertas siguen intactas.
        assertThat(cat("Grasa Termica Cooler Master MasterGel Maker Nano")).isEqualTo("Cooler");
        assertThat(cat("Pasta Termica Noctua NT-H2 3.5g")).isEqualTo("Cooler");
    }

    @Test
    @DisplayName("T14: una RAM que nombra el perfil de overclock del fabricante de CPU no es un CPU")
    void ramConPerfilDeOverclockNoEsUnCpu() {
        // Medido, pc-builder-homelab T14: 35 filas de RAM caían en CPU vía
        // " amd "/" intel " (KW_CPU) por nombrar "AMD EXPO"/"Intel XMP" — el
        // perfil de overclock del stick, no la marca de un procesador. El
        // sustantivo líder "memoria" + un token DDR real gana antes.
        assertThat(cat("Memoria RAM Kingston Fury Beast 16GB 5600 Mhz DDR5 CL36 Negra AMD EXPO"))
                .isEqualTo("RAM");
        assertThat(cat("Memoria Corsair DDR5 32GB (2x16GB) 6000MHz Vengeance CL36 Black Intel XMP 3.0 / AMD EXPO"))
                .isEqualTo("RAM");
        assertThat(cat("Memoria KingDian DDR4 8GB 2666MHz CL22 Solo Intel")).isEqualTo("RAM");
        // Un CPU de verdad sigue siendo CPU: no arranca con "memoria".
        assertThat(cat("Procesador Amd Ryzen 5 8500G 5.0GHz Turbo AM5")).isEqualTo("CPU");
        // Una notebook con RAM en el nombre sigue ganando por su propio
        // líder (Notebook corre antes que RAM en el bloque tech).
        assertThat(cat("Notebook Lenovo IdeaPad 3 8GB RAM 512GB SSD")).isEqualTo("Notebook");
    }

    @Test
    @DisplayName("T15: una RAM \"con disipador\" no es un Cooler")
    void ramConDisipadorNoEsUnCooler() {
        // El sustantivo líder "memoria" + un token DDR ya la manda a RAM
        // antes de llegar a KW_COOLER (mismo mecanismo que T14) — "disipador"
        // acá describe un accesorio del stick, no el producto.
        assertThat(cat("MEMORIA 8GB DDR4 3200 MACROVIP MAX C/DISIPADOR")).isEqualTo("RAM");
        assertThat(cat("Memoria Hiksemi 8gb 3200 Mhz Armor C/disipador Black Ddr4")).isEqualTo("RAM");
    }

    @Test
    @DisplayName("T15: un disco \"con disipador\" no es un Cooler")
    void discoConDisipadorNoEsUnCooler() {
        // Medido: "disipador" bare en KW_COOLER se comía cualquier disco que
        // publicitara el suyo — el sustantivo líder (HD/Disco) gana antes.
        assertThat(cat("HD SSD 1TB WD BLACK SN850X C/DISIPADOR M.2 NVME PCIE GEN4"))
                .isEqualTo("Almacenamiento");
        assertThat(cat("Disco Solido SSD Hiksemi FUTURE X LITE 2TB M.2 NVMe Con Disipador"))
                .isEqualTo("Almacenamiento");
    }

    @Test
    @DisplayName("T15: un Joystick/Auricular de marca \"Cooler Master\" no es un Cooler")
    void perifericoDeMarcaCoolerMasterNoEsUnCooler() {
        // "Cooler Master" es la marca, no el producto — el sustantivo líder
        // (Joystick/Auricular) gana antes que el bare "cooler" de KW_COOLER.
        assertThat(cat("Joystick Cooler Master Storm Controller Xbox One/Series/PC"))
                .isEqualTo("Joystick");
        assertThat(cat("Auricular Cooler Master CH351 Headset")).isEqualTo("Auricular");
    }

    @Test
    @DisplayName("T15: una controladora de fans no es un Cooler")
    void controladoraDeFansNoEsUnCooler() {
        // Es un hub/accesorio para controlar coolers ya instalados, no un
        // cooler en sí — mismo trato que un bracket (KW_ACCESORIO_LIDER).
        assertThat(cat("Controladora Cooler Master A1 Gen 2 ARGB P/Fan Coolers"))
                .isNotEqualTo("Cooler");
    }
}
