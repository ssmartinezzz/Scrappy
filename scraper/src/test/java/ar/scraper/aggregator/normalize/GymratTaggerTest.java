package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GymratTagger#esGymrat(String, String, String, String, String)}.
 *
 * <p>Covers rule 3 (site in {@code SiteClassification.GYM_SITIOS}) for the
 * gym-only stores Monkyforce and Fursten, plus the hard footwear guard.</p>
 */
@Epic("Normalization")
@Feature("Gymrat Tagging")
@DisplayName("GymratTagger — gym-only site tagging")
class GymratTaggerTest {

    private final GymratTagger tagger = new GymratTagger();

    // Product names deliberately carry no KW_TRAINING_ROPA keyword and no
    // GYM_MARCAS brand, so only the site rule can produce the tag.

    @Test
    void ropaDeMonkyforceEsGymrat() {
        Allure.parameter("nombre", "Epic Polo Negro");
        Allure.parameter("sitioKey", "monkyforce");
        Allure.parameter("cat", "Remeras");
        assertThat(tagger.esGymrat("Epic Polo Negro", "monkyforce", "Remeras", "", null))
                .isTrue();
    }

    @Test
    void ropaDeFurstenEsGymrat() {
        Allure.parameter("nombre", "Benie Basic");
        Allure.parameter("sitioKey", "fursten");
        Allure.parameter("cat", "Remeras");
        assertThat(tagger.esGymrat("Benie Basic", "fursten", "Remeras", "", null))
                .isTrue();
    }

    @Test
    void calzadoDeSitioGymNoEsGymrat() {
        // Hard guard: gymrat is clothing only — footwear never gets the tag,
        // even when the store is in GYM_SITIOS.
        Allure.parameter("nombre", "Urbanas Retro");
        Allure.parameter("sitioKey", "monkyforce");
        Allure.parameter("cat", "Zapatillas");
        assertThat(tagger.esGymrat("Urbanas Retro", "monkyforce", "Zapatillas", "", null))
                .isFalse();
    }

    @Test
    void ropaDeSitioNoGymSinSenalNoEsGymrat() {
        Allure.parameter("nombre", "Remera Basica Lisa");
        Allure.parameter("sitioKey", "tussy");
        Allure.parameter("cat", "Remeras");
        assertThat(tagger.esGymrat("Remera Basica Lisa", "tussy", "Remeras", "", null))
                .isFalse();
    }
}
