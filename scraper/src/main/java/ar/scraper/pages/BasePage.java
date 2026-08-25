package ar.scraper.pages;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public abstract class BasePage {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Page page;
    protected final int timeoutMs;

    protected BasePage(Page page, int timeoutMs) {
        this.page = page;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Ceiling for the best-effort networkidle settle, deliberately decoupled
     * from the page timeout.
     *
     * <p>Reaching this limit is caught and ignored below — the wait is a
     * courtesy, not a requirement — yet it used to be handed the full 30 s
     * page timeout. Sites that never reach network idle (analytics beacons,
     * polling, open sockets: Playwright discourages the state for exactly this
     * reason) therefore burned the entire budget, and did so on <em>every</em>
     * page of pagination.
     *
     * <p>Measured on one listing page each: freres and sporting both sat at
     * 30 011 ms, while midway, tussy, harvey and vcp settled in
     * 1 963-3 601 ms. Two of six sites paid the ceiling and four never came
     * close, so 8 s leaves better than twice the headroom of the slowest
     * healthy site while returning ~22 s per page on the stalled ones.
     *
     * <p>Cutting the wait short is safe for listing pages because
     * {@link #scrollToBottom()} independently waits for the grid to stop
     * growing before anything is extracted.
     */
    static final int NETWORK_IDLE_MAX_MS = 8_000;

    protected void navigateTo(String url) {
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout(Math.min(NETWORK_IDLE_MAX_MS, timeoutMs)));
        } catch (Exception e) {
            log.debug("networkidle timeout: {}", e.getMessage());
        }
    }

    protected String safeText(ElementHandle el, String selector) {
        try {
            ElementHandle t = el.querySelector(selector);
            if (t == null) return "";
            String s = t.textContent();
            return s == null ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }

    protected String safeAttr(ElementHandle el, String attr) {
        try {
            String v = el.getAttribute(attr);
            return v == null ? "" : v.trim();
        } catch (Exception e) { return ""; }
    }

    protected List<ElementHandle> queryAllWithRetry(String selector, int retries) {
        for (int i = 0; i < retries; i++) {
            try {
                List<ElementHandle> els = page.querySelectorAll(selector);
                if (!els.isEmpty()) return els;
                page.waitForTimeout(600);
            } catch (Exception e) {
                log.debug("retry {}: {}", i + 1, e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * JS Promise that scrolls down in 600 px increments and resolves when the
     * image count stops growing (stable DOM) or after 20 checks — whichever
     * comes first. Replaces the old fixed Java-side {@code waitForTimeout} loop
     * so timing is driven by actual DOM mutations rather than a fixed delay.
     */
    /** Gap between polls while the grid settles. */
    static final int SCROLL_POLL_MS = 250;

    /**
     * Consecutive quiet polls required before the page is called settled.
     * {@code 4 x 250 ms = 1 s}, measured as the cheapest sufficient value: 1 s
     * of quiet finds the same products that 2 s and 3 s do, while a 1.2 s wait
     * spread over slower polls terminated early on a page that was still
     * momentarily static at its first render.
     */
    static final int SCROLL_STABLE_POLLS = 4;

    /** Hard ceiling, so a genuinely infinite feed cannot stall the whole run. */
    static final int SCROLL_MAX_MS = 20000;

    /**
     * Scrolls until the grid stops growing <em>and</em> the viewport has actually
     * reached the bottom of the document.
     *
     * <p>The previous heuristic advanced a fixed 600 px per poll and gave up
     * after 20 polls, so it could never travel past 12 000 px however long the
     * page was, and it treated a plateau in the image count as "done" even
     * while the document was still growing. Measured against a live Tiendanube
     * listing it halted at 4 378 px of an 8 653 px page after 5.8 s, having seen
     * 139 images; stepping a full viewport and waiting for the document height
     * to settle reaches the true bottom in 5.3 s and sees 170. It was dropping
     * products, not merely running slowly.
     */
    private static final String SCROLL_JS = """
            () => new Promise(resolve => {
              const STEP_MS = %d, STABLE = %d, MAX_MS = %d;
              const t0 = Date.now();
              let lastCount = -1, lastHeight = -1, stable = 0;
              const check = () => {
                const count  = document.querySelectorAll('img').length;
                const height = document.body.scrollHeight;
                const atBottom = window.scrollY + window.innerHeight >= height - 2;
                if (count === lastCount && height === lastHeight && atBottom) {
                  if (++stable >= STABLE) { resolve(); return; }
                } else { stable = 0; }
                lastCount = count; lastHeight = height;
                if (Date.now() - t0 > MAX_MS) { resolve(); return; }
                window.scrollBy(0, window.innerHeight);
                setTimeout(check, STEP_MS);
              };
              check();
            })
            """.formatted(SCROLL_POLL_MS, SCROLL_STABLE_POLLS, SCROLL_MAX_MS);

    protected void scrollToBottom() {
        try {
            page.evaluate(SCROLL_JS);
        } catch (Exception e) { log.debug("scroll: {}", e.getMessage()); }
    }

    /**
     * JS function that recursively traverses shadow roots and concatenates the
     * {@code outerHTML} of every element — including those nested inside
     * shadow DOM trees that are invisible to normal {@code querySelectorAll}.
     */
    private static final String SHADOW_DOM_JS = """
            () => {
              function flatten(root) {
                const els = Array.from(root.querySelectorAll('*'));
                return els.flatMap(el => el.shadowRoot
                  ? [el, ...flatten(el.shadowRoot)] : [el]);
              }
              return flatten(document.body).map(el => el.outerHTML).join('');
            }
            """;

    /**
     * Returns a flat HTML string containing the {@code outerHTML} of every
     * element reachable through the document's shadow DOM tree.
     * Additive — no existing {@link BasePage} method signatures change.
     */
    protected String flattenedShadowHtml() {
        return (String) page.evaluate(SHADOW_DOM_JS);
    }

    protected Optional<Double> parsePrecio(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            // Fast-path: entero puro (viene de data-price de TN/Shopify)
            String trimmed = raw.replaceAll("[^0-9.,]", "").trim();
            if (trimmed.isBlank()) return Optional.empty();

            // Si es un entero sin separadores, parsear directo
            if (trimmed.matches("\\d+")) {
                double v = Double.parseDouble(trimmed);
                return v > 0 ? Optional.of(v) : Optional.empty();
            }

            String s = trimmed;
            if (s.contains(".") && s.contains(",")) {
                // 12.500,00 → separador miles=punto, decimal=coma
                s = s.replace(".", "").replace(",", ".");
            } else if (s.contains(",") && !s.contains(".")) {
                // 12500,00 → decimal con coma (poco común en AR pero por si acaso)
                long commas = s.chars().filter(c -> c == ',').count();
                s = commas == 1 ? s.replace(",", ".") : s.replace(",", "");
            } else if (s.contains(".") && !s.contains(",")) {
                long dots = s.chars().filter(c -> c == '.').count();
                if (dots > 1) {
                    // 1.200.000 → separador de miles repetido
                    s = s.replace(".", "");
                } else {
                    int dotPos = s.indexOf('.');
                    // Si hay exactamente 3 dígitos después del punto → separador de miles
                    if (s.length() - dotPos - 1 == 3) s = s.replace(".", "");
                }
            }
            double v = Double.parseDouble(s);
            return v > 0 ? Optional.of(v) : Optional.empty();
        } catch (Exception e) { return Optional.empty(); }
    }

    protected String absoluteUrl(String href, String baseUrl) {
        if (href == null || href.isBlank()) return "";
        if (href.startsWith("http")) return href;
        try {
            java.net.URI base = java.net.URI.create(baseUrl);
            return base.getScheme() + "://" + base.getHost() + (href.startsWith("/") ? "" : "/") + href;
        } catch (Exception e) { return href; }
    }

    protected String domain(String url) {
        return dominioPublico(url);
    }

    /**
     * Helper estático para extraer "scheme://host" de una URL, sin depender
     * de una instancia de BasePage.
     */
    static String dominioPublico(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) { return url; }
    }
}
