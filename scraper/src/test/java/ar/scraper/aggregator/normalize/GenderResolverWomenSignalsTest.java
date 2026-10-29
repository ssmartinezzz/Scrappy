package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenderResolverWomenSignalsTest {

    private final GenderResolver resolver = new GenderResolver();

    @Test
    void wmnTokenIsWomen() {
        assertThat(resolver.resolver("", "Short Wmn Training", "Short")).isEqualTo("mujer");
    }

    @Test
    void girlTokenIsWomen() {
        assertThat(resolver.resolver("", "Girl Tank", "Musculosa")).isEqualTo("mujer");
    }

    @Test
    void croptopIsWomen() {
        assertThat(resolver.resolver("", "CROPTOP 4REAL NEGRO", "Top")).isEqualTo("mujer");
        assertThat(resolver.resolver("", "Crop Top Training", "Top")).isEqualTo("mujer");
    }

    @Test
    void brasTokenIsWomen() {
        assertThat(resolver.resolver("", "Bras Cruzado", "Top")).isEqualTo("mujer");
    }

    @Test
    void signalsAreWholeTokens() {
        assertThat(resolver.resolver("", "Brasilia Duffel", "Mochila")).isEmpty();
        assertThat(resolver.resolver("", "Girlfriend Fit Short", "Short")).isEmpty();
        assertThat(resolver.resolver("", "Wmnbox Remera", "Remera")).isEmpty();
    }

    @Test
    void womenStillBeatsMenOrdering() {
        assertThat(resolver.resolver("", "Remera Women Wmn", "Remera")).isEqualTo("mujer");
        assertThat(resolver.resolver("", "Remera Men", "Remera")).isEqualTo("hombre");
    }

    @Test
    void explicitMenSignalStillBeatsNewWomenSignals() {
        assertThat(resolver.resolver("", "Remera Hombre Girl Print", "Remera")).isEqualTo("hombre");
    }

    @Test
    void nameWomenSignalBeatsSiteGenero() {
        assertThat(resolver.resolver("hombre", "Crop Top Hoodie", "Buzo")).isEqualTo("mujer");
        assertThat(resolver.resolver("unisex", "Woman Force Short", "Short")).isEqualTo("mujer");
        assertThat(resolver.resolver("", "PACK BUZO V-ROOT YOURSELF BLACK + CALZA LARGA 4REAL BLACK + MEDIAS WHITE",
                "Conjunto")).isEqualTo("mujer");
        assertThat(resolver.resolver("hombre", "Botas De Snowboard Dc Shoes Lotus Mujer Verde Menta", "Botas"))
                .isEqualTo("mujer");
    }

    @Test
    void menWordInNameBlocksTheNameWomenStep() {
        assertThat(resolver.resolver("hombre", "Calza adidas Techfit de Hombre", "Calza")).isEqualTo("hombre");
        assertThat(resolver.resolver("", "Remera Hombre Girl Print", "Remera")).isEqualTo("hombre");
        assertThat(resolver.resolver("hombre", "Remera Men Wmn", "Remera")).isEqualTo("hombre");
    }

    @Test
    void nameWomenStepMatchesWholeWordsOnly() {
        assertThat(resolver.resolver("hombre", "Calzado Urbano", "Zapatilla")).isEqualTo("hombre");
        assertThat(resolver.resolver("hombre", "Womanizer Tee", "Remera")).isEqualTo("hombre");
    }

    @Test
    void bareTopIsNotAWomenSignal() {
        assertThat(resolver.resolver("hombre", "Top Ls Velvet Monogram Brown", "Musculosa")).isEqualTo("hombre");
    }

    @Test
    void infantilStillBeatsTheNameWomenStep() {
        assertThat(resolver.resolver("", "Calza Kids Nena", "Calza")).isEqualTo("infantil");
    }
}
