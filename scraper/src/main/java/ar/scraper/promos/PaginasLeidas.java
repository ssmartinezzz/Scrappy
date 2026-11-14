package ar.scraper.promos;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Pattern;

/** Fetch + sanity check shared by the sources: a block page or an empty body is a failed read. */
final class PaginasLeidas {

    private static final int TEXTO_MINIMO = 40;
    /** A real storefront carries far more text than an interstitial ("verify you are human"). */
    private static final int TEXTO_DE_INTERSTICIAL = 300;
    private static final Pattern TITULO_DE_BLOQUEO = Pattern.compile(
            "<title>\\s*(Just a moment|Attention Required|Access Denied)|__cf_chl", Pattern.CASE_INSENSITIVE);
    // Ordinary pages embed these too (invisible reCAPTCHA v3, Cloudflare's injected script):
    // they only mean "blocked" on a page with almost no text of its own.
    private static final Pattern WIDGET_DE_DESAFIO = Pattern.compile(
            "challenge-platform|g-recaptcha|h-captcha|cf-turnstile|px-captcha", Pattern.CASE_INSENSITIVE);

    private PaginasLeidas() {}

    /** Throws {@link LecturaFallidaException} unless {@code url} came back as a real page. */
    static Document leer(PaginaWeb web, String url) {
        String html;
        try {
            html = web.html(url);
        } catch (LecturaFallidaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LecturaFallidaException("no se pudo leer " + url + ": " + e.getMessage(), e);
        }
        if (StringUtils.isBlank(html)) throw new LecturaFallidaException("cuerpo vacío en " + url);
        if (TITULO_DE_BLOQUEO.matcher(html).find()) throw new LecturaFallidaException("página de bloqueo en " + url);
        Document doc = Jsoup.parse(html, url);
        int texto = doc.text().strip().length();
        if (texto < TEXTO_DE_INTERSTICIAL && WIDGET_DE_DESAFIO.matcher(html).find()) {
            throw new LecturaFallidaException("página de bloqueo en " + url);
        }
        if (texto < TEXTO_MINIMO && doc.select("[data-variants]").isEmpty()) {
            throw new LecturaFallidaException("página sin contenido visible en " + url);
        }
        return doc;
    }
}
