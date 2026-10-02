package ar.scraper.pages;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * {@link QloudPage} and {@link TechStorePage} only handled the protocol-relative {@code //host/...}
 * form, so FullH4rd — which serves {@code src="/img/productos/3/{slug}-0.jpg"} — wrote a bare path
 * into {@code productos.imagen_url} on every row it ever produced.
 */
final class ImageUrl {

    private ImageUrl() {}

    static String absolutize(String raw, String baseUrl) {
        if (raw == null) return "";
        String src = raw.trim();
        if (src.isEmpty()) return "";

        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;

        if (StringUtils.isBlank(baseUrl)) return "";
        return baseUrl.replaceAll("/+$", "") + "/" + src.replaceAll("^/+", "");
    }

    static String primera(Pattern img, String card, String baseUrl) {
        var m = img.matcher(card);
        return m.find() ? absolutize(m.group(1), baseUrl) : "";
    }
}
