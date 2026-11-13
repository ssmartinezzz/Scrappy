package ar.scraper.scrapers;

import ar.scraper.promos.LecturaFallidaException;
import ar.scraper.promos.PaginaWeb;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * The site's own Playwright page as a {@link PaginaWeb}: it already sits behind the egress proxy and
 * carries the Cloudflare clearance, so the promo read needs no second HTTP client and no new egress path.
 */
final class PlaywrightPaginaWeb implements PaginaWeb {

    private static final int ASENTAR_MAX_MS = 5_000;

    private final Page page;
    private final int timeoutMs;

    PlaywrightPaginaWeb(Page page, int timeoutMs) {
        this.page = page;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String html(String url) {
        Response respuesta;
        try {
            respuesta = page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(timeoutMs).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (RuntimeException e) {
            throw new LecturaFallidaException("no se pudo abrir " + url + ": " + e.getMessage(), e);
        }
        if (respuesta == null) throw new LecturaFallidaException("sin respuesta de " + url);
        if (respuesta.status() >= 400) throw new LecturaFallidaException("HTTP " + respuesta.status() + " en " + url);
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(Math.min(ASENTAR_MAX_MS, timeoutMs)));
        } catch (RuntimeException e) {
            // best effort: a page that never idles still has its server-rendered banners
        }
        return page.content();
    }
}
