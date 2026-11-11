package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every name below is a real product title from hadriatica.com (or, for the "must not move" cases,
 * a title from a tech/home site that shares a noun with it).
 *
 * <p>Game titles have no leading noun ("Xenoblade Chronicles 2 - Nintendo Switch 2"), so
 * {@code Videojuego} is decided by a platform signal, and only when no accessory noun is the object.</p>
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("gaming and collectible categories")
@DisplayName("CategoryClassifier — Videojuego, Consola, Figura Coleccionable, Peluche, Juego de Mesa")
class HadriaticaCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Videojuego")
    @ValueSource(strings = {
        "Xenoblade Chronicles 2 - Nintendo Switch 2",
        "Kid Icarus Uprising - Nintendo 3DS",
        "Dead or Alive 5+ - PS VITA",
        "Senran Kagura Burst Re: Newal - Tailor Made Edition - PlayStation 4",
        "SWITCH LIMITED RUN #81: DOOM 64 CLASSIC EDITION",
        "Switch Limited Run #100: No More Heroes 2 Collector&#039;s Edition",
        "Apollo Justice: Ace Attorney Trilogy Switch",
        "Mario Party 10 - Wii U",
        "Harvest Moon: The Tale of Two Towns - Nintendo DS",
        "NBA 2K21 - PS4",
        "Super Mario Galaxy 2 (Wii)",
        "Zero Time Dilemma PS VITA",
        "Stardew Valley for Nintendo Switch(TM) - Nintendo Switch - FANGAMER",
        "Kunitsu-Gami: Path of the Goddess - Nintendo Switch 2 (GAME - KEY CARD)",
        "Tom Clancys Ghosty Recon Shadow Wars 3DS",
        "Toy Story: Retro Roundup! + Toy Story 3 Complete Edition DOUBLE PACK - Nintendo Switch 2",
        "Advance Wars 1+2: Re-Boot Camp - Nintendo Switch",
        "Puzzle &amp; Dragons Z + Puzzle &amp; Dragons Super Mario Bros Edition - Nintendo 3ds",
        "Nintendo Switch Sports Resort - Nintendo Switch 2",
        "Chibi-Robo!: Zip Lash with Chibi-Robo amiibo bundle - Nintendo 3DS Bundle Edition",
        "Videojuego Fifa 26 Edicion Estandar"
    })
    @DisplayName("a title with a platform signal is a Videojuego")
    void gamesAreIdentifiedByPlatform(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Videojuego");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Videojuego")
    @ValueSource(strings = {
        "10 Ft Type-C Charge Cable For PS5 Xbox Series X Nintendo Switch - Hyperkin",
        "Joy-Con Comfort Grip - Black",
        "NYXI Odyssey Case (For Hyperion 3 Controller)",
        "Nintendo Switch AC Adapter Official",
        "Nintendo Switch 2 Carrying Case &amp; Screen Protector - Nintendo",
        "Memoria 256GB MicroSDXC UHS-I Card for Nintendo Switch Sandisk",
        "Nintendo Switch 2 USB Camera - HORI",
        "Grip for Nintendo New 3DS (No XL)",
        "Auricular HyperX Cloud Stinger Core Inalámbrico White PC - PS4 - PS5",
        "Volante Logitech G29 PS5 PC Con Pedalera",
        "Amiibo Super Smash Bros. - Mario",
        "Funko Pop! Games: Pokemon - Charmander",
        "TP-Link Switch Gigabit 8 Puertos TL-SG108",
        "KVM Switch HDMI 4 Puertos",
        "Motor Base Fanatec Clubsport Dd Qr2 12 Nm Para Pc Y Xbox",
        "TP-Link Gigabit Desktop Switch"
    })
    @DisplayName("hardware and accessories that mention a platform are not games")
    void accessoriesAreNotGames(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Videojuego");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Joystick")
    @ValueSource(strings = {
        "Nano Wired Controller - Mario Medley (Power A)",
        "Nano Wired Controller - Toon Link ( Power A)",
        "Nintendo Switch 2 Joy-Con 2 (L)/(R):Blue/Light Yellow",
        "Nintendo Joy-Con (L/R) - Neon Green / Neon Green",
        "Control N64 - Old Skool - Green",
        "Control GameCube - Old Skool - Purple",
        "Snes Classic Controller - TTX",
        "Switch 2 HORIPAD Turbo: Black - HORI",
        "Joystick Cooler Master Storm Controller Xbox One/Series/PC",
        "Control Razer Wolverine V3 Pro Xbox Wireless White"
    })
    @DisplayName("a console controller stays a Joystick instead of falling into a garment category")
    void controllersAreJoysticks(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Joystick");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Consola")
    @ValueSource(strings = {
        "STEAM DECK OLED - 1TB",
        "Nintendo Switch Lite - Blue",
        "Nintendo Switch - OLED Model: Mario Red Edition",
        "Nintendo Switch - OLED Model w/ Neon Red &amp; Neon Blue Joy-Con",
        "Nintendo Switch 2 Console - Pokémon ZA Bundle (USA)",
        "XBOX Consola Xbox Series S",
        "Consola Sony PlayStation 5 Slim 1TB Digital Edition",
        "Powkiddy RGB20S Handheld Console, Mini Retro Games Console - 3,5 inch PANTALLA IPS (WHITE) Consola retro portátil con más de 20 mil juegos",
        "Anbernic RG35XX Gray 64Gb",
        "NES Classic Edition - NES MINI",
        "Sega Genesis Mini - Sega Genesis Classic",
        "NEO GEO MINI - International",
        "New 3DS XL - Majoras Mask Edition",
        "Consola Nintendo Switch OLED Blanco",
        "Consola Lenovo Legion Go S AMD Ryzen Z2 Go 4.3GHz 16GB LPDDR5X-6400 1TB 8\" 120Hz WIFI 6E Bluetooth 5.3 SteamOS"
    })
    @DisplayName("gaming hardware is a Consola")
    void consolesAreHardware(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Consola");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Consola")
    @ValueSource(strings = {
        "Funda para consola PS5 de silicona",
        "SWITCH - OLED - SCREEN PROTECTOR - TEMPERED GLASS (KMD)",
        "AC Adapter for Nintendo WII U Console - Multivoltaje",
        "Xenoblade Chronicles X - Wii U",
        "Consola de madera 2 cajones con espejo"
    })
    @DisplayName("an accessory, a game or a console table is not a Consola")
    void notConsoles(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Consola");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Figura Coleccionable")
    @ValueSource(strings = {
        "POP Disney: Lilo &amp; Stitch - Stitch - (Hanukkah) - (Dreidel)",
        "Amiibo Super Smash Bros. - Mario",
        "Amiibo Cards Animal Crossing - Series 3 - PACK de 6 Unidades",
        "World of Nintendo - 4 inch - Toad",
        "Nendoroid - Kirby Cafe Special Edition #2598",
        "Nendoroid Surprise Haikyu!! Nationals Arc - Good Smile Company (One Blind Box)",
        "Detective Conan The Phantom Thief - Q Posket",
        "Dragon Ball - ChiChi (Ver.A) - Q Posket",
        "One Piece - Girls On Vacation - Nico Robin - Banpresto",
        "Dragon Ball Z - Bandai Spirits - Ichibansho Figure Cell (1st Form)",
        "Hunter x Hunter - Machi Noodle Stopper Prize Figure",
        "Phoenix Wright Ace Attorney Figma Action Figure",
        "Figure Kirby Friends 2 Soft Vinyl - Ice Kirby - BANDAI",
        "Super Mario 30th Anniversary Gold Mario Action Figure by TAITO Mario Gold",
        "Sonic The Hedgehog - Sonic - Figura Articulada 17 cm",
        "Gashapon Yoshi"
    })
    @DisplayName("collectible figures are a Figura Coleccionable")
    void figures(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Figura Coleccionable");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Juguete")
    @ValueSource(strings = {
        "FUNKO POP! SAINT SEIYA GOLD AQUARIUS HYOGA 1425",
        "Funko Bitty POP! Star Wars - 4 Pack Miniature Vinyl Figures (Han Solo)",
        "Funko Pop! Games: Pokemon - Charmander",
        "Funko Pop! Llavero: Fortnite - Havoc"
    })
    @DisplayName("Funko is always a Juguete, figure or keychain alike")
    void funkoIsAToy(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Juguete");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Figura Coleccionable")
    @ValueSource(strings = {
        "Funko Pop Keychain: Game of Thrones - Jon Snow",
        "Keychain - One Piece - Trafalgar Law 3D",
        "Figura decorativa de jardin Gnomo 30 cm",
        "Plush Pokemon - Riolu",
        "Pokémon TCG: Mega Lucario ex Figure Collection"
    })
    @DisplayName("a keychain, a garden ornament, a plush or a card box is not a figure")
    void notFigures(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Figura Coleccionable");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Peluche")
    @ValueSource(strings = {
        "POKEMON I CHOOSE YOU! POKEMON GET PLUSH - DRAGONITE (TAKARA TOMY)",
        "Plush Pokemon Official TOMY -Heal Ball 5 Inch",
        "Plush Kirby All Star Collection 5.5inch",
        "CUTEEZE PLUSH MICKEY MOUSE",
        "Banjo-Kazooie Plush Set - FANGAMER",
        "BANPRESTO Plush Pokemon Bewear 10inch",
        "Mocchi Mocchi - Animal Crossing BLATHERS Mega 15 inch Plush Stuffed Toy, Almohadon Grande",
        "Peluche Oso Teddy 40 cm Suave"
    })
    @DisplayName("plush toys are a Peluche, not a Juguete")
    void plush(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Peluche");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Peluche")
    @ValueSource(strings = {
        "Kirby Plush Keychain 3.5&quot; Standard",
        "LLavero Super Mario - Luigi 5&quot; Plush Dangler",
        "Peluche para perro con sonido",
        "Colchón Inflable 2 Plazas 152x203x46 Cm Intex Comfort Plush",
        "Jibbitz Crocs Pixar Lotso Plush"
    })
    @DisplayName("a plush keychain or a pet toy is not a Peluche")
    void notPlush(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Peluche");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Juego de Mesa")
    @ValueSource(strings = {
        "Pokémon TCG: Mega Evolution - Chaos Rising Booster Pack (10 Cards)",
        "POKEMON TCG - POKEMON 151 - BOOSTER PACK - 10 CARTAS",
        "Pokemon TCG: Scarlet &amp; Violet 10.5 Black Bolt Elite Trainer Box",
        "Pokémon TCG: 30th Celebration - Expansion Pack (1 BOOSTER) JAPAN",
        "BANDAI DRAGON BALL TCG SUPER CARD GAME - FUSION WORLD - BEYOND GENERATIONS Booster Pack (B24) ENGLISH",
        "BANDAI Digimon TCG: Versus Monsters (EX-09)",
        "One Piece Card Game: Carrying On His Will (OP-13) One Piece TCG English",
        "Juego de Mesa Catan Edicion Clasica",
        "Exploding Kittens Board Game",
        "Juego de Cartas UNO Original"
    })
    @DisplayName("board games and trading cards are a Juego de Mesa")
    void boardAndCardGames(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Juego de Mesa");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Juego de Mesa")
    @ValueSource(strings = {
        "Juego de llaves tubo 12 piezas Stanley",
        "Booster de señal WiFi Repetidor 300Mbps",
        "Pre Workout Booster 300g Ena"
    })
    @DisplayName("a tool set, a wifi booster or a supplement is not a Juego de Mesa")
    void notBoardGames(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Juego de Mesa");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiterString = " => ", textBlock = """
        Puzzle Super Mario - 250 piezas - Jigsaw Puzzle - Viene con lata! => Juguete
        LEGO Harry Potter Dobby The House-Elf Building Toy Set => Juguete
        Hot Wheels Mario Kart Glider Bowser Diecast Car [Standard Kart + Bowser Kite] => Juguete
        MousePad Sanrio Retro => Mousepad
        RELOJ CASIO VINTAGE CA-53WF-8B => Reloj
        Medias Largas Sanrio - Kuromi => Medias
        Cable HDMI-HDMI M-M Comun 1.5M => Cable
        Juego de Peces Aros 33,5x28,5x30 Cm Bestway => Juguete
        Figura De Elefante En Material 3D Blanco Elkas Deco => Decoración de Jardín
        Figura Mono Mizaru 16.5 Cm Gres Outzen => Decoración de Jardín
        """)
    @DisplayName("the categories that were already working keep working")
    void existingCategoriesStay(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }
}
