package ar.scraper.pages;

/**
 * fix-zero-yield-tech-sites, C6 (spec: {@code tech-site-catalog-coverage} /
 * Maximus, Scenario "A session-gate failure is an explicit, logged failure
 * — never a silent empty result", R1).
 *
 * <p>Thrown by {@link TechStorePage#parseMaximusPayload(String)} when the
 * API's {@code d} field is not the expected paginated-listing shape — most
 * often the GlobalBluePoint session/module gate
 * ({@code "-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}), returned
 * with HTTP 200 by a cookie-less call. Deliberately UNCAUGHT inside
 * {@code TechStorePage}: it propagates through {@code scrape(page)} into
 * {@link ar.scraper.scrapers.BaseScraper#ejecutar}'s catch, landing in
 * {@code ScrapeResult.error} instead of being indistinguishable from a
 * legitimately empty category.</p>
 */
public class MaximusPayloadException extends RuntimeException {
    public MaximusPayloadException(String dPrefix) {
        super("Maximus API devolvió un payload inesperado (posible session/module gate), primeros 120 chars: "
                + dPrefix);
    }
}
