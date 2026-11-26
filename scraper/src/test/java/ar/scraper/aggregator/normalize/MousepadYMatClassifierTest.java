package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Every name comes from the catalogue. The classifier decides the category, MousepadReglas and EscritorioReglas the sub. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Mousepad y Mat and Escritorio Motorizado — category and sub-category over real names")
class MousepadYMatClassifierTest {

    private static final String CATEGORIA = "Mousepad y Mat";

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Mouse Pad Fantech MP64 Basic 640x210x2mm Black                       | Mousepad",
        "Mouse Pad Gamer Razer Gigantus V2 XXL                                | Mousepad XL",
        "Mouse Pad Corsair MM700 Extended 3XL 1220x620mm RGB                  | Mousepad RGB",
        "Mousepad Fantech MP70 XL 700x300x3mm Pink                            | Mousepad XL",
        "Mouse Pad Running Hockey Padel                                       | Mousepad",
        "MousePad Sanrio Retro                                                | Mousepad",
        "Mouse Pad Ergonómico Con Apoyamuñecas De Gel                         | Apoyamuñecas",
        "Mouse Pad Ergonómico De Gel                                          | Mousepad Ergonómico",
        "Mat Antifatiga                                                       | Antifatiga",
        "Mat Antifatiga Pro                                                   | Antifatiga",
        "Mat de Escritorio de Ecocuero + Corcho                               | Mat de Escritorio",
        "Mat Board                                                            | Mat de Escritorio",
        "ACE ATTORNEY Courtroom Desk Mat - FANGAMER                           | Mat de Escritorio",
        "Wrist Pad Genesis Rubid 400 TKL                                      | Apoyamuñecas",
    })
    void padsAndMats(String nombre, String sub) {
        assertThat(cat(nombre)).isEqualTo(CATEGORIA);
        assertThat(subs.resolver(nombre, CATEGORIA)).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] stray \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Kit Apoyamuñecas                                                     | Apoyamuñecas",
        "Pad Apoya Muñecas Genesis Rubid 400                                  | Apoyamuñecas",
        "Wrist Rest PRO KIT                                                   | Apoyamuñecas",
        "Ergonomic Wrist Rest                                                 | Apoyamuñecas",
        "APOYAMUÑECAS AULA F75 BLUE ACOLCHADO                                 | Apoyamuñecas",
        "Apoyamuñeca Aula Wrist Rest F75 Degrade Gray                         | Apoyamuñecas",
        "Playstation Heritage Desk Mat, 30 x 80 cm - Official Licensed Product | Mat de Escritorio",
        "Mat para escritorio                                                  | Mat de Escritorio",
    })
    void straysFromOtherCategories(String nombre, String sub) {
        assertThat(classifier.normalizarCategoria("Otros", nombre)).as("with a stored Otros").isEqualTo(CATEGORIA);
        assertThat(classifier.normalizarCategoria("Teclado", nombre)).as("with a stored Teclado").isEqualTo(CATEGORIA);
        assertThat(subs.resolver(nombre, CATEGORIA)).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] kit \"{0}\" is not a pad")
    @ValueSource(strings = {
        "COMBO GAMER MOUSE LOGITECH G502 + MOUSEPAD G240 280X340MM",
        "COMBO TECLADO+MOUSE+MOUSEPAD AUREOX LIFELIGHT GAMING GC1000",
        "Combo Gamer 4 En 1 Solarmax Gc4p1503 (teclado + Mouse + Mousepad + Auricular)",
        "Combo Mouse y Mouse Pad Redragon M601W Wireless",
        "Kit Gamer Teclado Mouse y Mouse Pad Onikuma Gaming TZ3006 RGB",
        "Trust Gaming Combo Gxt 783 Izza Mouse+mouse Pad /usb",
        "Combo Genesis - Teclado Mecánico THOR 230 TKL RGB + Pad Apoya Muñecas Rubid 400 TKL",
    })
    void aKitStaysWhereItsLeadingNounPutsIt(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo(CATEGORIA);
    }

    @Test
    @DisplayName("a keyboard that comes with a wrist rest is a keyboard")
    void keyboardWithWristRestStaysKeyboard() {
        assertThat(cat("Teclado Cableado SABLUTE K21 Pro Letras Grandes Retroiluminado Con Reposamuñecas")).isEqualTo("Teclado");
    }

    @Test
    @DisplayName("a pad named with other peripherals but not sold as a kit stays a pad")
    void padForAKeyboardAndMouseIsStillAPad() {
        assertThat(cat("Mouse Pad Gamer Para Teclado Y Mouse Xl 90x40")).isEqualTo(CATEGORIA);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Escritorio Gamer Regulable Eléctrico Evolabs Liftdesk One Madera     | Motorizado",
        "Escritorio Eléctrico Dellorean 120cm Pasadena Altura Regulable Motor Certificado 80kg | Motorizado",
        "Escritorio Cougar Oficina Electrico E-star Blanco 120cm Gris          | Motorizado",
        "Escritorio Doble Motor Standing Desk 160 cm                           | Motorizado",
        "Escritorio Regulable En Altura Evolabs Liftdesk Core Evodsk-100lc     |",
        "ESCRITORIO COOLERMASTER GD160 ARGB REGULABLE                          |",
        "Standing Desk Pro V2                                                  |",
        "Escritorio Gamer Raptor Glow Station RGB Con Elevacion 80kg           |",
        "Escritorio Regulable Sin Motor                                        |",
        "Escritorio Standing Desk Manual Regulable                             |",
    })
    void motorizedDeskNeedsAProvenMotor(String nombre, String sub) {
        assertThat(cat(nombre)).isEqualTo("Escritorio");
        assertThat(subs.resolver(nombre, "Escritorio")).isEqualTo(sub == null ? "" : sub);
    }

    @Test
    @DisplayName("a desk frame that says electric is Motorizado when the site tree already files it under Escritorio")
    void electricFrame() {
        assertThat(subs.resolver("Estructura Para Escritorio Electrico Turboblender Tb-Odesk1210", "Escritorio")).isEqualTo("Motorizado");
    }

    @Test
    @DisplayName("the old categories are gone from the vocabulary")
    void oldCategoriesAreGone() {
        assertThat(ar.scraper.classification.CategoryGroups.canonicalCategories())
                .contains(CATEGORIA).doesNotContain("Mousepad", "Mat Escritorio");
        assertThat(ar.scraper.classification.CategoryGroups.categoriasOficina()).contains(CATEGORIA);
        assertThat(ar.scraper.classification.CategoryGroups.esCategoriaTech(CATEGORIA)).isFalse();
    }
}
