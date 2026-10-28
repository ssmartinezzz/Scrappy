package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarcasPicksGymratGeneroTest {

    private static Product p(String id, String categoria, String genero, String sub) {
        return Product.builder()
                .sitio("Fuark").nombre(categoria + " " + id).precio(1000)
                .url("https://s/" + id).imagenUrl("img").categoria(categoria).genero(genero)
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Fuark").rubro("indumentaria")
                .gymrat(true).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).subCategoria(sub)
                .visual(Product.VisualAttrs.EMPTY).build();
    }

    private static AggregatedResult catalogo(Product... ps) {
        return new AggregatedResult(List.of(ps), Map.of(), Map.of(), null, 0, 0);
    }

    private static List<String> urls(List<MarcasPicksDtos.MejoresCategoria> cards, String categoria) {
        return cards.stream().filter(c -> categoria.equals(c.getCategoria()))
                .flatMap(c -> c.getPicks().stream()).map(MarcasPicksDtos.Pick::getUrl).sorted().toList();
    }

    private final AggregatedResult cat = catalogo(
            p("h", "Short", "hombre", ""),
            p("m", "Short", "mujer", ""),
            p("u", "Short", "unisex", ""),
            p("b", "Short", "", ""),
            p("n", "Short", null, ""),
            p("k", "Short", "infantil", ""));

    @Test
    void hombreKeepsHombreUnisexAndBlank() {
        var cards = MarcasPicksView.mejores(cat, "", true, "hombre");
        assertThat(urls(cards, "Short")).containsExactly("https://s/b", "https://s/h", "https://s/n", "https://s/u");
    }

    @Test
    void mujerKeepsMujerUnisexAndBlank() {
        var cards = MarcasPicksView.mejores(cat, "", true, "mujer");
        assertThat(urls(cards, "Short")).containsExactly("https://s/b", "https://s/m", "https://s/n", "https://s/u");
    }

    @Test
    void aHombreShortAndAMujerShortLandInDifferentViews() {
        var h = urls(MarcasPicksView.mejores(cat, "", true, "hombre"), "Short");
        var m = urls(MarcasPicksView.mejores(cat, "", true, "mujer"), "Short");
        assertThat(h).contains("https://s/h").doesNotContain("https://s/m");
        assertThat(m).contains("https://s/m").doesNotContain("https://s/h");
    }

    @Test
    void withoutGeneroNothingChanges() {
        var sin = MarcasPicksView.mejores(cat, "", true);
        assertThat(urls(sin, "Short")).hasSize(5);
        assertThat(MarcasPicksView.mejores(cat, "", true, "")).usingRecursiveComparison().isEqualTo(sin);
    }

    @Test
    void compressionTeesGetTheirOwnCardOnlyInGymratMode() {
        var ca = catalogo(
                p("c1", "Remera", "hombre", "compresión"),
                p("c2", "Remera", "hombre", "compresión"),
                p("r1", "Remera", "hombre", "deportiva"),
                p("t1", "Musculosa", "hombre", "compresión"));

        var gym = MarcasPicksView.mejores(ca, "", true, "hombre");
        assertThat(urls(gym, "Remera de compresión")).containsExactly("https://s/c1", "https://s/c2");
        assertThat(urls(gym, "Remera")).containsExactly("https://s/r1");
        assertThat(urls(gym, "Musculosa")).containsExactly("https://s/t1");

        var plano = MarcasPicksView.mejores(ca, "", false);
        assertThat(urls(plano, "Remera")).hasSize(3);
        assertThat(urls(plano, "Remera de compresión")).isEmpty();
    }

    @Test
    void otrosNeverAppearsInGymratPicksButStaysInPlainOnes() {
        var ca = catalogo(p("o", "Otros", "", ""), p("r", "Remera", "", ""));
        assertThat(urls(MarcasPicksView.mejores(ca, "", true, "hombre"), "Otros")).isEmpty();
        assertThat(urls(MarcasPicksView.mejores(ca, "", false), "Otros")).containsExactly("https://s/o");
    }

    @Test
    void keyNormalizesGeneroAndSeparatesIt() {
        assertThat(MejoresKey.de(1, "", true, " Hombre ")).isEqualTo(MejoresKey.de(1, "", true, "hombre"));
        assertThat(MejoresKey.de(1, "", true, "mujer")).isNotEqualTo(MejoresKey.de(1, "", true, "hombre"));
        assertThat(MejoresKey.de(1, "", true, "otro")).isEqualTo(MejoresKey.de(1, "", true, null));
        assertThat(MejoresKey.de(1, "", true)).isEqualTo(MejoresKey.de(1, "", true, null));
    }
}
