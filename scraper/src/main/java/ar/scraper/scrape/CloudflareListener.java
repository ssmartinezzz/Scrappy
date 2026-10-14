package ar.scraper.scrape;

/** Where a scraper reports the Cloudflare barriers it meets; the scraper never knows who listens. */
@FunctionalInterface
public interface CloudflareListener {

    void reportar(StatusEvent.ScrapeCloudflare evento);

    CloudflareListener NOOP = evento -> { };
}
