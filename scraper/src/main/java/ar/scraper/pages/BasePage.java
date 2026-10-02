package ar.scraper.pages;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BasePage {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final Page page;
    protected final int timeoutMs;

    /**
     * Ceiling for the best-effort networkidle settle, deliberately decoupled from the page timeout.
     * Sites that never reach network idle (analytics beacons, polling, open sockets:
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
     * JS Promise that scrolls down in 600 px increments and resolves when the image count stops
     * growing (stable DOM) or after 20 checks — whichever comes first.
     */
    /** Gap between polls while the grid settles. */
    static final int SCROLL_POLL_MS = 250;

    /** Consecutive quiet polls required before the page is called settled. */
    static final int SCROLL_STABLE_POLLS = 4;

    /** Hard ceiling, so a genuinely infinite feed cannot stall the whole run. */
    static final int SCROLL_MAX_MS = 20000;

    /**
     * The previous heuristic advanced a fixed 600 px per poll and gave up after 20 polls, so it
     * could never travel past 12 000 px however long the page was, and it treated a plateau in the
     * image count as "done" even while the document was still growing.
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

    protected String flattenedShadowHtml() {
        return (String) page.evaluate(SHADOW_DOM_JS);
    }

    protected Optional<Double> parsePrecio(String raw) {
        if (StringUtils.isBlank(raw)) return Optional.empty();
        try {
            String trimmed = raw.replaceAll("[^0-9.,]", "").trim();
            if (trimmed.isBlank()) return Optional.empty();

            // Si es un entero sin separadores, parsear directo
            if (trimmed.matches("\\d+")) {
                double v = Double.parseDouble(trimmed);
                return v > 0 ? Optional.of(v) : Optional.empty();
            }

            String s = trimmed;
            if (s.contains(".") && s.contains(",")) {
                s = s.replace(".", "").replace(",", ".");
            } else if (s.contains(",") && !s.contains(".")) {
                // 12500,00 → decimal con coma (poco común en AR pero por si acaso)
                long commas = s.chars().filter(c -> c == ',').count();
                s = commas == 1 ? s.replace(",", ".") : s.replace(",", "");
            } else if (s.contains(".") && !s.contains(",")) {
                long dots = s.chars().filter(c -> c == '.').count();
                if (dots > 1) {
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
        if (StringUtils.isBlank(href)) return "";
        if (href.startsWith("http")) return href;
        try {
            java.net.URI base = java.net.URI.create(baseUrl);
            return base.getScheme() + "://" + base.getHost() + (href.startsWith("/") ? "" : "/") + href;
        } catch (Exception e) { return href; }
    }

    protected String domain(String url) {
        return dominioPublico(url);
    }

    static String dominioPublico(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) { return url; }
    }
}
