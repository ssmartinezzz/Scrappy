package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.BrandExtractor;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SubcategoryResolver;

/**
 * Test-only wiring for {@link NormalizerService}. Not a production facade —
 * {@code NormalizerService}'s constructor takes real collaborator
 * dependencies (Work Unit 8 of the aggregator SOLID modularization), so
 * {@code new NormalizerService()} no longer compiles from test code. This
 * factory news up all eight normalize collaborators and returns a fully
 * wired orchestrator, letting integration-style tests exercise real
 * behavior without a Spring context.
 */
public final class NormalizerServiceTestFactory {

    private NormalizerServiceTestFactory() {}

    public static NormalizerService create() {
        return new NormalizerService(
                new PackQuantityDetector(),
                new CategoryClassifier(),
                new BrandExtractor(),
                new GenderResolver(),
                new SizeNormalizer(),
                new SubcategoryResolver(),
                new RubroResolver(),
                new GymratTagger());
    }
}
