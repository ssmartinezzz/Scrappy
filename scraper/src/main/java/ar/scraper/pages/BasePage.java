package ar.scraper.pages;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
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

    /** The main-resource response; null when the page cannot tell (that is unknown, not a failure). */
    protected Response navigateTo(String url) {
        Response respuesta = page.navigate(url, new Page.NavigateOptions()
                .setTimeout(timeoutMs)
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions()
                            .setTimeout(Math.min(NETWORK_IDLE_MAX_MS, timeoutMs)));
        } catch (Exception e) {
            log.debug("networkidle timeout: {}", e.getMessage());
        }
        return respuesta;
    }

    /** A non-2xx answer to a navigation the catalog depends on. */
    static final class RespuestaHttpException extends RuntimeException {
        private final int status;

        RespuestaHttpException(int status) {
            super("HTTP " + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    static final int RETRY_AFTER_MAX_S = 60;
    static final int RETRY_AFTER_DEFECTO_S = 10;
    static final int REINTENTOS_429 = 2;

    /**
     * Ceiling for all the 429 waiting one page object does, so a site that throttles every page
     * cannot spend the per-site timeout asleep.
     */
    static final long ESPERA_429_TOTAL_MAX_MS = 180_000;

    private long espera429Ms;

    /**
     * Navigates and demands a 2xx. A 429 waits out Retry-After (capped) and retries a bounded
     * number of times; any other non-2xx, or a 429 that persists, throws. A 404 past the first
     * page is how a catalog ends, not a failure. A null response is unknown and passes.
     */
    protected void navegarExigiendoOk(String url, int pagina) {
        for (int intento = 0; ; intento++) {
            Response respuesta = navigateConReintentos(url);
            if (respuesta == null) return;
            int status = respuesta.status();
            if (status >= 200 && status < 300) return;
            if (status == 404 && pagina > 1) return;
            if (status != 429 || intento >= REINTENTOS_429) throw new RespuestaHttpException(status);

            long esperaMs = 1000L * segundosDeReintento(respuesta);
            if (espera429Ms + esperaMs > ESPERA_429_TOTAL_MAX_MS) throw new RespuestaHttpException(status);
            espera429Ms += esperaMs;
            log.warn("HTTP 429 en {}: espero {}s antes de reintentar ({}/{})",
                    url, esperaMs / 1000, intento + 1, REINTENTOS_429);
            page.waitForTimeout(esperaMs);
        }
    }

    static int segundosDeReintento(Response respuesta) {
        String valor = respuesta.headers() == null ? null : respuesta.headers().get("retry-after");
        if (StringUtils.isBlank(valor)) return RETRY_AFTER_DEFECTO_S;
        try {
            return Math.max(1, Math.min(RETRY_AFTER_MAX_S, Integer.parseInt(valor.trim())));
        } catch (NumberFormatException e) {
            return RETRY_AFTER_DEFECTO_S;
        }
    }

    private static final int MAX_MOTIVO = 200;

    /** Total attempts, first one included. */
    static final int NAV_INTENTOS = 3;
    static final int NAV_ESPERA_MS = 1_500;

    /**
     * Retries only {@code net::ERR_*}: a dropped connection or a network switch heals on its own.
     * A timeout already burned the page budget, and a closed target never comes back.
     */
    protected Response navigateConReintentos(String url) {
        for (int intento = 1; ; intento++) {
            try {
                return navigateTo(url);
            } catch (RuntimeException e) {
                if (intento >= NAV_INTENTOS || !esErrorDeRedTransitorio(e)) throw e;
                log.debug("navegación a {} falló ({}), intento {}/{}", url, e.getMessage(), intento, NAV_INTENTOS);
                page.waitForTimeout((long) NAV_ESPERA_MS * intento);
            }
        }
    }

    static boolean esErrorDeRedTransitorio(Throwable e) {
        return e.getMessage() != null && e.getMessage().contains("net::ERR_");
    }

    private String truncamiento;

    /** Null unless a pagination loop gave up on an error: what was kept is a partial catalog. */
    public String truncamiento() {
        return truncamiento;
    }

    /** For a fallback that replaces the whole attempt: the cut it recorded no longer describes the result. */
    protected void limpiarTruncamiento() {
        truncamiento = null;
    }

    /** Keeps the first cut only; later ones are usually the same outage seen again. */
    protected void registrarTruncamiento(int pagina, Exception e) {
        registrarCorte("paginación cortada en p" + pagina, e);
    }

    /** For a cut that is not a page of a listing, such as the store's home. */
    protected void registrarTruncamiento(String donde, Exception e) {
        registrarCorte("catálogo cortado en " + donde, e);
    }

    private void registrarCorte(String donde, Exception e) {
        String motivo = StringUtils.abbreviate(
                StringUtils.substringBefore(StringUtils.defaultString(e.getMessage(), e.getClass().getSimpleName()), "\n"),
                MAX_MOTIVO);
        log.warn("{}: {}", donde, motivo);
        if (truncamiento == null) truncamiento = donde + ": " + motivo;
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

    /** Un challenge de Cloudflare no es un catálogo vacío (captura real en fixtures/cloudflare). */
    static boolean esBloqueoCloudflare(String html) {
        if (StringUtils.isBlank(html)) return false;
        return html.contains("challenge-platform")
                || html.contains("__cf_chl")
                || html.contains("<title>Just a moment...")
                || html.contains("<title>Attention Required");
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
