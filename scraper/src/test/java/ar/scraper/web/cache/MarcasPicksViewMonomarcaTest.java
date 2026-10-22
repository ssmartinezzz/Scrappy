package ar.scraper.web.cache;

import ar.scraper.aggregator.NormalizerService;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
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
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarcasPicksView — a single-brand store shows up as a brand, end to end from the normalizer")
class MarcasPicksViewMonomarcaTest {

    @Test
    void monomarcaStoreAppearsAndIsSearchable() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "monkyforce", new Sitio("Monkyforce", "monkyforce", "monkyforce", false, null, "config", true)));
        var normalizer = new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(),
                new BrandExtractor(), new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(registry), new GymratTagger(), registry);
        List<Product> crudos = List.of(
                Product.builder().sitio("Monkyforce").nombre("Buzo Oversize").precio(100).url("http://m/1")
                        .categoria("Buzo").build(),
                Product.builder().sitio("Monkyforce").nombre("Remera Lisa").precio(200).url("http://m/2")
                        .categoria("Remera").build());
        var r = new AggregatedResult(normalizer.normalizar(crudos), Map.of(), Map.of(), null, 0, 0);

        var marcas = MarcasPicksView.marcas(r, "", "monky", "");

        assertThat(marcas).extracting(MarcasPicksDtos.Marca::getMarca).containsExactly("Monkyforce");
    }
}
