package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manga titles carry no leading noun ("ONE PIECE 003 - IVREA"), and the words that name them are also
 * garment keywords ("one piece" is a swimsuit, "short stories" a short). Names are real hadriatica.com titles.
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("Libro and the gaming accessories that used to land in garments")
@DisplayName("CategoryClassifier — Libro, and the Hadriatica false positives")
class LibroCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subcategorias = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Libro")
    @ValueSource(strings = {
        "ONE PIECE 003 - IVREA",
        "ONE PIECE 004 - IVREA",
        "DRAGON BALL ED. KANZENBAN 08 - IVREA",
        "CAPTAIN TSUBASA 06 - IVREA",
        "BLUE LOCK 38 - IVREA",
        "DANDADAN 05",
        "CARDCAPTOR SAKURA ED. DELUXE 04",
        "CAPTAIN TSUBASA 05",
        "FULLMETAL ALCHEMIST 01",
        "FRIEREN - MAS ALLA DEL FINAL 04",
        "YU YU HAKUSHO ED. KANZENBAN 02",
        "THE LEGEND OF ZELDA 03: MAJORA´S MASK / A LINK TO THE PAST",
        "spyxfamily eyes only databook",
        "demon slayer 23",
        "INIO ASANO SHORT STORIES",
        "Animal Crossing Official Sticker Book (Nintendo)",
        "Animal Crossing- New Horizons - Official Activity Book - Libro con stickers",
        "The Art of Splatoon Hardcover - 320 páginas, tapa dura",
        "Animal Crossing: New Horizons Official Companion Guide",
        "Libro de cocina vegetariana"
    })
    @DisplayName("manga and books are a Libro, even when their words are garment keywords")
    void books(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Libro");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Libro")
    @ValueSource(strings = {
        "Remera One Piece Luffy 3",
        "Malla Entera One Piece Speedo",
        "Buzo Nike Tech Fleece Manga Larga",
        "Estantería para libros 5 estantes",
        "Shampoo Volumen 400ml",
        "FIFA 23 - PS5",
        "Hot Wheels 5-Pack Mario Kart",
        "Funko Pop! One Piece - Luffy 1234",
        "One Piece Card Game: Carrying On His Will (OP-13) One Piece TCG English",
        "Keychain - One Piece - Trafalgar Law 3D",
        "Switch Limited Run #100: No More Heroes 2 Collector&#039;s Edition",
        "Fuente 500w 80 Plus 01",
        "Camisa Manga Corta Lino Jano C",
        "Mangas cortas adidas Remera Workout Essentials de Cuello Redondo Mujer",
        "Manga para Desagote 1 1/2\" 25 Mts Mavi",
        "Mangas Saucony Fortify Unisex",
        "Canilla Manga 1/2 FV",
        "Top Kamp Crop Manga Corta Seamless",
        "Libro Decorativo Botánico 25 Cm VGO"
    })
    @DisplayName("a garment, a game, a figure or a tech part that shares a word is not a Libro")
    void notBooks(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Libro");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiterString = " => ", textBlock = """
        Malla Entera One Piece Speedo => Malla
        Vaso Cafe 790ml Grooved PP Pokemon => Bazar
        Vaso Cafe Doble 520ml. -Pared Grande- "Pokemon" => Bazar
        Vaso Plástico - Central Perk => Bazar
        Belkin - Nintendo Switch 2 Case w/Integrated 10,000mAh Power Bank Charger, Portable Hard Shell Protective Carrying Case - SAGE => Otros
        Belkin - Gaming Travel Bag - NINTENDO SWITCH 2 Black => Otros
        Joystick Inalámbrico Gamesir G7 Pro Zenless Zone Zero Xbox Pc Android + Dock Y Funda => Joystick
        """)
    @DisplayName("a cup is Bazar and a console case is not a garment")
    void falsePositivesFromTheRealScrape(String nombre, String esperado) {
        assertThat(cat(nombre)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("a Libro with a manga signal gets the sub-categoria Manga; a plain book does not")
    void mangaSubcategory() {
        assertThat(subcategorias.resolver("ONE PIECE 003 - IVREA", "Libro")).isEqualTo("Manga");
        assertThat(subcategorias.resolver("DANDADAN 05", "Libro")).isEqualTo("Manga");
        assertThat(subcategorias.resolver("YU YU HAKUSHO ED. KANZENBAN 02", "Libro")).isEqualTo("Manga");
        assertThat(subcategorias.resolver("Animal Crossing Official Sticker Book (Nintendo)", "Libro")).isNotEqualTo("Manga");
    }
}
