package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GymratTagger#esGymrat(String, String, String, String, String)}.
 *
 * <p>Covers rule 3 (site in {@code SiteClassification.GYM_SITIOS}) for the
 * gym-only stores Monkyforce and Fursten, plus the hard footwear guard.</p>
 */
class GymratTaggerTest {

    private final GymratTagger tagger = new GymratTagger();

    // Product names deliberately carry no KW_TRAINING_ROPA keyword and no
    // GYM_MARCAS brand, so only the site rule can produce the tag.

    @Test
    void ropaDeMonkyforceEsGymrat() {
        assertThat(tagger.esGymrat("Epic Polo Negro", "monkyforce", "Remeras", "", null))
                .isTrue();
    }

    @Test
    void ropaDeFurstenEsGymrat() {
        assertThat(tagger.esGymrat("Benie Basic", "fursten", "Remeras", "", null))
                .isTrue();
    }

    @Test
    void calzadoDeSitioGymNoEsGymrat() {
        // Hard guard: gymrat is clothing only — footwear never gets the tag,
        // even when the store is in GYM_SITIOS.
        assertThat(tagger.esGymrat("Urbanas Retro", "monkyforce", "Zapatillas", "", null))
                .isFalse();
    }

    @Test
    void ropaDeSitioNoGymSinSenalNoEsGymrat() {
        assertThat(tagger.esGymrat("Remera Basica Lisa", "tussy", "Remeras", "", null))
                .isFalse();
    }
}
