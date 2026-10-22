package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NormalizerService — the brand of a single-brand store is the store")
class NormalizerMonomarcaMarcaTest {

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "monkyforce", new Sitio("Monkyforce", "monkyforce", "monkyforce", false, null, "config", true),
                "fuark", new Sitio("Fuark", "fuark", "tiendanube", false, null, "config", true),
                "midway", new Sitio("Midway", "midway", "tiendanube", false, null, "config", false)));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(registry), new GymratTagger(), registry);
    }

    private String marcaDe(String sitio, String nombre, String marcaScrapeada) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).precio(1000.0)
                .url("http://" + sitio + ".test/" + nombre.hashCode()).marca(marcaScrapeada)
                .categoria("Remera").talles(List.of("M")).build();
        return normalizer.normalizar(List.of(p)).get(0).marca();
    }

    @Test
    @DisplayName("a monomarca product gets the site nombre as brand")
    void monomarcaGetsSiteNombre() {
        assertThat(marcaDe("Monkyforce", "Remera Lisa Algodón", "")).isEqualTo("Monkyforce");
    }

    @Test
    @DisplayName("the site wins over a stray curated match in the name")
    void siteBeatsCuratedMatch() {
        assertThat(marcaDe("Fuark", "Buzo Champion Oversize", "")).isEqualTo("Fuark");
    }

    @Test
    @DisplayName("a non-monomarca site still abstains, and still honours a curated brand")
    void multimarcaUnchanged() {
        assertThat(marcaDe("Midway", "Remera Lisa Algodón", "")).isEmpty();
        assertThat(marcaDe("Midway", "Remera Nike Dri-Fit", "")).isEqualTo("Nike");
    }
}
