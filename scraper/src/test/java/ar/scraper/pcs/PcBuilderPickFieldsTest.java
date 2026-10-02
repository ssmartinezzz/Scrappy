package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PcBuilder picks — absent product fields become empty strings and protocol-relative images get https")
class PcBuilderPickFieldsTest {

    private PcPick pickDe(String imagen) {
        Product p = Product.builder()
                .sitio("TestSitio").nombre("Gabinete Adata XPG Invader X BTF Black").precio(90_000)
                .precioOriginal(null).url("https://t/g").imagenUrl(imagen).categoria("Gabinete").genero("")
                .talles(List.of()).ml(Product.MlScore.EMPTY).marca(null).rubro("tecnologia").gymrat(false)
                .build();
        PcBuild build = new PcBuilder().armar(List.of(p), 0, false, Set.of(), null,
                new PreferenciasDeArmado(null, null, null, null, null, null, null, null, null, null));
        return build.picks().stream().filter(x -> x.slot().equals("gabinete")).findFirst().orElseThrow();
    }

    @Test
    void nullImageAndBrandBecomeEmpty() {
        PcPick pick = pickDe(null);

        assertThat(pick.img()).isEmpty();
        assertThat(pick.marca()).isEmpty();
        assertThat(pick.sitio()).isEqualTo("TestSitio");
    }

    @Test
    void protocolRelativeImageGetsHttps() {
        assertThat(pickDe("//cdn/x.jpg").img()).isEqualTo("https://cdn/x.jpg");
    }
}
