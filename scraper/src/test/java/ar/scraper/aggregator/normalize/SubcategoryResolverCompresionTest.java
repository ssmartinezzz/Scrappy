package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubcategoryResolverCompresionTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    @Test
    void compressionRemeraIsItsOwnSubcategory() {
        assertThat(resolver.resolver("Remera Compression Training", "Remera")).isEqualTo("compresión");
        assertThat(resolver.resolver("Remera de Compresión Gym", "Remera")).isEqualTo("compresión");
    }

    @Test
    void compressionMusculosaIsItsOwnSubcategory() {
        assertThat(resolver.resolver("Compression Tank Turtleneck", "Musculosa")).isEqualTo("compresión");
    }

    @Test
    void plainTrainingTeeStaysDeportiva() {
        assertThat(resolver.resolver("Remera Training Dri-Fit", "Remera")).isEqualTo("deportiva");
        assertThat(resolver.resolver("Musculosa Gym", "Musculosa")).isEqualTo("deportiva");
    }

    @Test
    void compressionIsOnlyForTops() {
        assertThat(resolver.resolver("Calza Compression", "Calza")).isNotEqualTo("compresión");
    }
}
