package ar.scraper.promos;

/**
 * The only way a promo source reads the web. Production backs it with the site's own Playwright page
 * (already behind the egress proxy, with its Cloudflare clearance); tests back it with captured HTML.
 * An implementation throws {@link LecturaFallidaException} on any HTTP error, timeout or closed page.
 */
@FunctionalInterface
public interface PaginaWeb {
    String html(String url);
}
