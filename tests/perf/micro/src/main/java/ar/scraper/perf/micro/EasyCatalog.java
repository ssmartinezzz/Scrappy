package ar.scraper.perf.micro;

import ar.scraper.aggregator.NormalizerService;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.model.Product;
import ar.scraper.pages.VtexPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

final class EasyCatalog {

    static final String PAYLOAD_RESOURCE = "/easy-payload.json.gz";
    static final String DOMAIN = "https://www.easy.com.ar";
    static final String SITE_KEY = "easy";

    final List<JsonNode> rawItems;
    final VtexPage vtexPage;
    final MethodHandle fromVtex;
    final List<Product> scraped;
    final NormalizerService normalizer;
    final List<Product> normalized;
    final CategoryClassifier categoryClassifier;
    final MethodHandle clasificar;
    final RubroResolver rubroResolver;
    final List<String> names;

    EasyCatalog() {
        try {
            rawItems = readPayload();
            vtexPage = new VtexPage(null, 1_000, SITE_KEY, DOMAIN, 0, Double.MAX_VALUE);
            Method fromVtexMethod = VtexPage.class.getDeclaredMethod("fromVtex", JsonNode.class, String.class);
            fromVtexMethod.setAccessible(true);
            fromVtex = MethodHandles.lookup().unreflect(fromVtexMethod);
            scraped = mapAll();
            SiteRegistry siteRegistry = SiteRegistry.forTesting(Map.of());
            rubroResolver = new RubroResolver(siteRegistry);
            categoryClassifier = new CategoryClassifier();
            Method clasificarMethod = CategoryClassifier.class.getDeclaredMethod("clasificar", String.class);
            clasificarMethod.setAccessible(true);
            clasificar = MethodHandles.lookup().unreflect(clasificarMethod);
            normalizer = new NormalizerService(
                    new PackQuantityDetector(),
                    categoryClassifier,
                    new BrandExtractor(),
                    new GenderResolver(),
                    new SizeNormalizer(),
                    new SubcategoryResolver(),
                    rubroResolver,
                    new GymratTagger(),
                    new SportResolver(),
                    siteRegistry);
            normalized = normalizer.normalizar(scraped);
            names = scraped.stream().map(Product::nombre).toList();
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    Optional<Product> map(JsonNode item) throws Throwable {
        return (Optional<Product>) fromVtex.invoke(vtexPage, item, DOMAIN);
    }

    private List<Product> mapAll() {
        List<Product> out = new ArrayList<>(rawItems.size());
        for (JsonNode item : rawItems) {
            try {
                map(item).ifPresent(out::add);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        return out;
    }

    private static List<JsonNode> readPayload() throws java.io.IOException {
        try (InputStream in = new GZIPInputStream(EasyCatalog.class.getResourceAsStream(PAYLOAD_RESOURCE))) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> items = new ArrayList<>(root.size());
            root.forEach(items::add);
            return items;
        }
    }
}
