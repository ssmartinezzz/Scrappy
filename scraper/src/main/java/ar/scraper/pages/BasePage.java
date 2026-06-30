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

    protected void navigateTo(String url) {
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
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
    private static final String SCROLL_JS = """
            () => new Promise(resolve => {
              let lastCount = 0, checks = 0;
              const maxChecks = 20;
              const check = () => {
                const count = document.querySelectorAll('img').length;
                if (count === lastCount || ++checks >= maxChecks) { resolve(); return; }
                lastCount = count;
                window.scrollBy(0, 600);
                setTimeout(check, 800);
              };
              check();
            })
            """;

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
     * Helper público estático para extraer "scheme://host" de una URL.
     * Usado por ScraperFactory.crearParaFavorito para resolver el dominio
     * de un producto favorito sin depender de una instancia de BasePage.
     */
    public static String dominioPublico(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) { return url; }
    }
}
