package ar.scraper.pages;

/**
 * add-morashop-and-fix-entreno-pagination, ADR-3 (spec: morashop leaf
 * discovery, "an empty leaf set is an explicit, logged failure — never a
 * silent empty catalogue").
 *
 * <p>Thrown by {@link MorashopPage} when the {@code /suplementos/} landing
 * yields no leaf categories. Morashop's whole catalogue is reached through
 * those leaves, so an empty set is not a small store — it means the landing
 * markup changed and the site is about to return zero products.
 *
 * <p>Deliberately UNCAUGHT inside {@link MorashopPage}, exactly as
 * {@link MaximusPayloadException} is inside {@code TechStorePage}: it
 * propagates through {@code scrape(page)} into
 * {@link ar.scraper.scrapers.BaseScraper#ejecutar}'s catch and lands in
 * {@code ScrapeResult.error}. That is the entire point. {@code SiteYieldGuard}
 * cannot cover this case — it only alerts when a site's count DROPS against
 * the previous run, so a site that yields zero on its first run, or one that
 * was already at zero, never trips it. Without this throw the failure looks
 * identical to a legitimately empty catalogue.
 */
public class MorashopDiscoveryException extends RuntimeException {
    public MorashopDiscoveryException(String seccionUrl) {
        super("Morashop: no se descubrio ninguna categoria hoja bajo " + seccionUrl
                + " — probablemente cambio el markup del landing. El catalogo entero "
                + "cuelga de esas hojas, asi que esto habria dado 0 productos en silencio.");
    }
}
