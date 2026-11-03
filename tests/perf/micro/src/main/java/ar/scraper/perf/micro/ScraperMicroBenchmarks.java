package ar.scraper.perf.micro;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 5)
@Fork(value = 5, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class ScraperMicroBenchmarks {

    private EasyCatalog catalog;
    private GroupingService groupingService;

    @Setup
    public void load() {
        catalog = new EasyCatalog();
        groupingService = new GroupingService(new ProductIdentity(), new JaccardSimilarity());
    }

    @Benchmark
    public void vtexPageToProduct(Blackhole blackhole) throws Throwable {
        for (JsonNode item : catalog.rawItems) {
            blackhole.consume(catalog.map(item));
        }
    }

    @Benchmark
    public void categoryClassifierClasificar(Blackhole blackhole) throws Throwable {
        for (String name : catalog.names) {
            blackhole.consume((String) catalog.clasificar.invoke(catalog.categoryClassifier, name));
        }
    }

    @Benchmark
    public void categoryClassifierNormalizarCategoria(Blackhole blackhole) {
        for (Product product : catalog.scraped) {
            blackhole.consume(catalog.categoryClassifier.normalizarCategoria(product.categoria(), product.nombre()));
        }
    }

    @Benchmark
    public void rubroResolverResolver(Blackhole blackhole) {
        for (Product product : catalog.normalized) {
            blackhole.consume(catalog.rubroResolver.resolver(EasyCatalog.SITE_KEY, product.categoria(), product.rubro()));
        }
    }

    @Benchmark
    public void normalizerServiceNormalizar(Blackhole blackhole) {
        blackhole.consume(catalog.normalizer.normalizar(catalog.scraped));
    }

    @Benchmark
    public void groupingServiceAgrupar(Blackhole blackhole) {
        blackhole.consume(groupingService.agrupar(catalog.normalized, false));
    }

    @Benchmark
    public void accentStripperStrip(Blackhole blackhole) {
        for (String name : catalog.names) {
            blackhole.consume(AccentStripper.strip(name));
        }
    }
}
