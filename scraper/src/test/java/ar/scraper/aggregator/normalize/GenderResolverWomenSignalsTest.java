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
}
