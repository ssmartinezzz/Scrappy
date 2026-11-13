package ar.scraper.promos;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Pattern;

/** Fetch + sanity check shared by the sources: a block page or an empty body is a failed read. */
final class PaginasLeidas {

    private static final int TEXTO_MINIMO = 40;
    private static final Pattern BLOQUEO = Pattern.compile(
            "challenge-platform|__cf_chl|<title>\\s*Just a moment|<title>\\s*Attention Required|g-recaptcha|"
                    + "h-captcha|cf-turnstile|<title>\\s*Access Denied|px-captcha",
            Pattern.CASE_INSENSITIVE);

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
        if (BLOQUEO.matcher(html).find()) throw new LecturaFallidaException("página de bloqueo en " + url);
        Document doc = Jsoup.parse(html, url);
        if (doc.text().strip().length() < TEXTO_MINIMO && doc.select("[data-variants]").isEmpty()) {
            throw new LecturaFallidaException("página sin contenido visible en " + url);
        }
        return doc;
    }
}
