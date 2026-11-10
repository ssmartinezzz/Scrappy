package ar.scraper.outfits;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tops left Musculosa for their own categories; the builder must keep treating them as upper-body garments. */
class OutfitTopCategoriesTest {

    @Test
    void topsAreTorsoBaseGarmentsKnownToTheBuilder() {
        for (String cat : new String[]{"Top", "Top Deportivo"}) {
            assertThat(OutfitService.CATEGORIA_SUBSLOT).as(cat).containsEntry(cat, OutfitService.SUBSLOT_TORSO_BASE);
            assertThat(OutfitService.KNOWN_CATEGORIAS).as(cat).contains(cat);
        }
    }
}
