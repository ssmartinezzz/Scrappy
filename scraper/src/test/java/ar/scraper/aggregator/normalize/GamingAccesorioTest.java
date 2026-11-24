package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names from the real catalogue. V135 repeats the token rules of GamingReglas in SQL and V135GamingAgreementTest runs both
 * over the same names, so a case added here that changes a rule needs the same change in the migration.
 */
@Epic("Normalization")
@Feature("Gaming classification")
@DisplayName("Gaming — Accesorio Gaming, console, controller and game, and their platform subs")
class GamingAccesorioTest {

    private final CategoryClassifier categorias = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return categorias.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Accesorio Gaming")
    @CsvSource(delimiter = '|', textBlock = """
        Case Nintendo Switch My Hero Academia
        Switch Carrying Case + Screen Protector Animal Crossing: New Horizons Aloha Edition
        Belkin - Gaming Travel Bag - NINTENDO SWITCH 2 Black
        Dehuka Dock De Carga Doble Para Controles Compatible Con Dualsense Ps5
        Soporte De Carga 4 En 1 Dehuka Compatible Con Ps5 Estación Multifuncional Con Ventilación Y Puertos Usb
        Funda Transparente Elite Para Control Compatible Con Ps5 Antigolpes Dehuka
        Kit Antideslizante 24 En 1 Para Control Compatible Con Ps5 Dehuka
        Cover Plates New Nintendo 3DS - Animal Crossing
        Nintendo Switch Lite Hybrid System Armor by HORI - Officially Licensed by Nintendo
        PS5 Digital Disc Drive Sony Unidad de disco para consola PS5
        Reproductor remoto Sony PlayStation Portal para la consola PS5
        Nintendo Eshop Card u$s 20
        4-in-1 Game Card Reader for Nintendo Switch/Switch OLED Console - UNITEK
        AC Adapter Nintendo Wii U - Multivoltaje TTX tech
        Av Cable y S-Video Cable para Sega Saturn
        Ventilador De Enfriamiento Para Consola Compatible Con Ps5 Dehuka
        Memoria 128GB MicroSDXC UHS-I Card for Nintendo Switch Sandisk
        Joy-Con 2 Controller Charge Grip for Switch 2
        Switch Joy-Con (L/R) - AA Battery Pack
        Controller Adapter for NES to NES Classic Edition - Hyperkin
        8Bitdo Classic Controller Travel Case for Pro 2 Controllers & Sn30 Pro+ Controller, Switch Pro Controller, PS5, PS4, Xbox One Controller and More
        Estación de recarga para el DualSense PS5
        Universal Retro 3-1 AC Adapter: NES / SNES / Genesis - Old Skool 110V
        """)
    void accessories(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Accesorio Gaming");
        assertThat(subs.resolver(nombre, "Accesorio Gaming")).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not an accessory")
    @CsvSource(delimiter = '|', textBlock = """
        Spirit Camera - Nintendo 3DS
        Pikmin 3 - Wii U (Original Cover)
        Hatsune Miku -Project DIVA- X - PS4 - INCLUYE POUCH de REGALO!
        PowerA Enhanced Wired Controller and Slim Case for Nintendo Switch - Fire Mario
        Nitro Deck - Handheld Pro Controller for Nintendo Switch and Switch OLED w/ Protective Carry Case
        Dualies - Pair of Motion Controllers with Included USB Type-C Charging Cable, Joy-Con for Nintendo Switch - BLACK
        Joystick Cooler Master Storm Controller Xbox One/Series/PC
        Adaptador Hdmi Switch 3 Entradas 1 Salida
        Cable De Red 15 Metros Cat.5e Rj45 Smart Tv Modem Router Ps4
        Switch Cisco CBS220 4GB 4X1G SFP
        Carrera First Nintendo Mario Kart Slot Car Race Track - Includes 2 Cars - Battery-Powered Beginner Toy Racing Set
        Teclado Mecanico Aula AG60 Pro Case Black Frosted Keycaps Switch Aether
        Remera Nike Nsw Club Mujer
        """)
    void notAccessories(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Accesorio Gaming");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
        Dr Kawashima's Brain Training (Nintendo Switch)                            | Videojuego
        Call of Duty: WWII -PS4                                                    | Videojuego
        Final Fantasy VII Rebirth - Deluxe Edition (PS5)                           | Videojuego
        Fire Emblem Warriors -Switch                                               | Videojuego
        Disco Elysium - The Final Cut for Nintendo Switch                          | Videojuego
        Super Mario Party + Pastel Purple / Pastel Green Joy-Con Bundle (Nintendo Switch) | Videojuego
        Joy-Con (L/R) – Neon Red/Neon Blue                                         | Joystick
        Retro Fighters - Contender Wireless Controller for Switch & PC - Black     | Joystick
        PowerA Wireless Controller for Nintendo Switch - Zelda - Sworn Protector   | Joystick
        Motor Base Fanatec Clubsport Dd Qr2 15 Nm Para Pc Y Xbox                   | Joystick
        Joystick Fantech Shooter Ii Gp13 Black Usb Pc Ps3                          | Joystick
        Consola R36S - blanco                                                      | Consola
        Consola Sony Playstation 5 Standard 825Gb Digital con Joystick             | Consola
        Consola Retro Play LT Clasica Juegos Nes Joystick Android Wifi - Negro     | Consola
        Nintendo New 3DS XL - Samus Edition                                        | Consola
        Wii Mini Red                                                               | Consola
        Super Nintendo Classic Edition - Snes classic - SNES MINI                  | Consola
        Nintendo Switch 2 + Mario Kart World Bundle                                | Consola
        PlayStation 5 Snapback - Japanese Inspired - Gorra                         | Gorra
        Pedalera de efectos Donner Ultra Dual Acoustic Preamp Looper               | Otros
        """)
    void categoryOfAFreshScrape(String nombre, String esperada) {
        assertThat(cat(nombre.strip())).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] [{1}] \"{0}\" -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
        Consola Nintendo Switch 2 - The Legend of Zelda - 40th Anniversary Edition  | Consola    | nintendo switch
        Nintendo Switch Lite - Coral                                                | Consola    | nintendo switch
        Consola Nintendo Portatil Nintendo Swithc Oled + Super Mario Bros Wonder    | Consola    | nintendo switch
        New 3DS XL Hyrule Edition (USA EDITION)                                     | Consola    | nintendo
        Consola Nintendo 64 Intec Gaming G1641 Salida HD                            | Consola    | nintendo
        Consola Ps5 Digital Sony Hw 2115 825 Gb Blanco                              | Consola    | playstation
        Consola Sony Playstation 5 Standard 825Gb Digital con Joystick              | Consola    | playstation
        Xbox Series X 1TB                                                           | Consola    | xbox
        STEAM DECK OLED - 1TB                                                       | Consola    | pc portátil
        Consola Lenovo Legion Go S AMD Ryzen Z2 Go 4.3GHz 16GB                      | Consola    | pc portátil
        Powkiddy RGB30 Handheld Console - NES/SNES/SEGA/N64/Playstation y más       | Consola    | emulador portátil
        Powkiddy Y6 2.4G Wireless Game Tv Stick Retro PS1 Family Portable           | Consola    | emulador tv
        Consola Portátil Dellorean Rebak 128GB Android 12                           | Consola    | emulador portátil
        Consola R36S - blanco                                                       | Consola    | emulador portátil
        Consola de Juegos Portátil 8 Bits Retroboy Family Super Mario 168           | Consola    | emulador portátil
        Consola Retro Level Up Nes Av 8 Bits 500 Juegos                             | Consola    | emulador tv
        SUPABOY Portable Pocket SNES Console                                        | Consola    | retro
        Nintendo Classic Mini Famicom                                               | Consola    | retro
        Consola Nintendo Portatil Nintendo Swithc Oled + 2 juegos                   | Consola    | nintendo switch
        Consola PS5 Slim 2 Joysticks HDMI                                           | Consola    | playstation
        Consola de Juegos Game Stick 4K 2 Joysticks Inalámbricos 10000 Juegos Retro | Consola    | emulador tv
        NES Classic Edition - NES MINI                                              | Consola    | retro
        My Arcade Micro Player Mini Arcade Machine: Galaga Video Game               | Consola    | retro
        Consola Portátil Gadnic 128GB Android 12 Pantalla IPS                       | Consola    |
        Super Mario Bros Wonder - Nintendo Switch                                   | Videojuego | switch
        EA SPORTS FC 27 - Nintendo Switch 2                                         | Videojuego | switch 2
        Pokemon Legends: Z-A: Switch 2 Edition - Switch 2                           | Videojuego | switch 2
        Naruto Shippuden Ultimate Ninja Storm 4: Road To Boruto NSW (Nintendo Switch) | Videojuego | switch
        Juego Para Ps5 Sony God Of War Ragnarok                                     | Videojuego | ps5
        Mafia III - PlayStation 4                                                   | Videojuego | ps4
        Dead or Alive 5+ - PS VITA                                                  | Videojuego | playstation retro
        Mario Kart 7 - Nintendo 3DS                                                 | Videojuego | nintendo 3ds
        Pokemon Mystery Dungeon Explorers of Time - Nintendo DS                     | Videojuego | nintendo 3ds
        Just Dance 2015 Wii U                                                       | Videojuego | wii
        Burnout Paradise Remastered - Xbox One                                      | Videojuego | xbox
        Super Smash Bros Brawl - WII                                                | Videojuego | wii
        Zelda Majoras Mask - Nintendo 64 N64                                        | Videojuego | retro
        Jostick Ps5 Sony Dualsense Chroma Pearl                                     | Joystick   | gamepad
        Nintendo Switch Pro Controller                                              | Joystick   | gamepad
        Nintendo Switch 2 Joy-Con 2 (L)/(R): Light Blue/Light Red - Nintendo        | Joystick   | gamepad
        8BitDo Ultimate 2 Wireless Controller - Black (81HE02)                      | Joystick   | gamepad
        Controlador GameSir G7 Pro Xbox Series X S Xbox One PC                      | Joystick   | gamepad
        Mando Genérico Inalámbrico Para Ps4 Colores Varios                          | Joystick   | gamepad
        Volante Logitech G29 Driving Force Racing PS5                               | Joystick   | volante
        PEDALERA LOGITECH G RS 941-000309                                           | Joystick   | volante
        Palanca de Cambios Moza HGP (7+R)                                           | Joystick   | volante
        """)
    void platformSub(String nombre, String categoria, String esperada) {
        assertThat(subs.resolver(nombre.strip(), categoria.strip())).isEqualTo(esperada == null ? "" : esperada.strip());
    }

    @Test
    @DisplayName("a sport word in a game or an accessory never becomes a sport sub")
    void noSportSub() {
        assertThat(subs.resolver("Tennis World Tour (Nintendo Switch)", "Videojuego")).isEqualTo("switch");
        assertThat(subs.resolver("Funda Para Nintendo Switch Tennis Edition", "Accesorio Gaming")).isEmpty();
    }
}
