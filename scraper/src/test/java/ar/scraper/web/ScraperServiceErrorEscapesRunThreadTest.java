package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvents;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@link Error} (the real case: {@code OutOfMemoryError} in the final upsert) used to slip past
 * the run thread's {@code catch (Exception)}, leaving the status RUNNING and the splash stuck.
 */
class ScraperServiceErrorEscapesRunThreadTest {

    private ScraperService serviceWhoseConfigThrows(Throwable t) {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        Mockito.when(config.getSitiosActivos()).thenAnswer(i -> { throw t; });
        return new ScraperService(config, Mockito.mock(ResultAggregator.class),
                Mockito.mock(ScrapeRunPort.class),
                Mockito.mock(ar.scraper.classification.SitiosPort.class),
                Mockito.mock(ar.scraper.ml.MlOutputPort.class),
                Mockito.mock(ar.scraper.classification.SiteRegistry.class),
                Mockito.mock(ar.scraper.catalog.ProductPort.class),
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class),
                e -> { }, StatusEvents.NONE);
    }

    private static void waitFor(ScraperService s, ScraperStatus wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && s.getStatus() != wanted) Thread.sleep(10);
    }

    @Test
    void anErrorInsideTheRunLeavesTheStatusInErrorNotRunning() throws Exception {
        ScraperService service = serviceWhoseConfigThrows(new OutOfMemoryError("Java heap space"));

        assertThat(service.iniciarScraping(Set.of())).isTrue();
        waitFor(service, ScraperStatus.ERROR);

        assertThat(service.getStatus()).isEqualTo(ScraperStatus.ERROR);
        assertThat(service.getStatusMsg()).contains("Java heap space");
    }

    @Test
    void theTurnIsReleasedSoANewRunCanStartAfterAnError() throws Exception {
        ScraperService service = serviceWhoseConfigThrows(new StackOverflowError());

        assertThat(service.iniciarScraping(Set.of())).isTrue();
        waitFor(service, ScraperStatus.ERROR);

        assertThat(service.iniciarScraping(Set.of())).isTrue();
    }

    @Test
    void anExceptionStillEndsInError() throws Exception {
        ScraperService service = serviceWhoseConfigThrows(new IllegalStateException("boom"));

        assertThat(service.iniciarScraping(Set.of())).isTrue();
        waitFor(service, ScraperStatus.ERROR);

        assertThat(service.getStatus()).isEqualTo(ScraperStatus.ERROR);
    }
}
