package ar.scraper.outfits;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Picks — absent product fields become empty strings and protocol-relative images get https")
class PickFieldDefaultsTest {

    private Product producto(String categoria, String imagen, String marca) {
        return Product.builder()
                .sitio("entreno").nombre("Whey Protein ENA 1kg").precio(10_000).precioOriginal(null)
                .url("https://test/whey").imagenUrl(imagen).categoria(categoria).genero("unisex")
                .talles(List.of()).ml(Product.MlScore.EMPTY).marca(marca).rubro("suplementos").gymrat(false)
                .build();
    }

    @Test
    void slotPickDefaultsNullImageAndCategoryToEmpty() {
        var pick = OutfitRules.toSlotPick("top", producto(null, null, null));

        assertThat(pick.img()).isEmpty();
        assertThat(pick.categoria()).isEmpty();
        assertThat(pick.slot()).isEqualTo("top");
        assertThat(pick.sitio()).isEqualTo("entreno");
        assertThat(pick.url()).isEqualTo("https://test/whey");
    }

    @Test
    void slotPickPrefixesProtocolRelativeImages() {
        var pick = OutfitRules.toSlotPick("top", producto("Remera", "//cdn/x.jpg", "Nike"));

        assertThat(pick.img()).isEqualTo("https://cdn/x.jpg");
        assertThat(pick.marca()).isEqualTo("Nike");
    }

    @Test
    void supplementPickDefaultsNullImageToEmptyAndPrefixesProtocolRelative() {
        var combo = new SupplementCombo(new RecommendationService());
        Set<String> soloPolvo = Set.of("Proteína en Polvo");

        var sinImagen = combo.armarComboSuplementos(List.of(producto("Proteína", null, "ENA")), 0, soloPolvo, Set.of());
        var relativa = combo.armarComboSuplementos(List.of(producto("Proteína", "//cdn/x.jpg", "ENA")), 0, soloPolvo, Set.of());

        assertThat(sinImagen).singleElement().satisfies(p -> {
            assertThat(p.img()).isEmpty();
            assertThat(p.marca()).isEqualTo("ENA");
        });
        assertThat(relativa).singleElement().satisfies(p -> assertThat(p.img()).isEqualTo("https://cdn/x.jpg"));
    }
}
