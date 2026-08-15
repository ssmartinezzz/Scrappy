package ar.scraper.pages;

/**
 * One owner (CODE-6) for turning whatever a listing put in {@code src} into a
 * URL the dashboard and the zero-shot visual classifier can actually fetch.
 *
 * <p>Every reader used to re-implement this join inline, and each stopped at a
 * different point: {@link QloudPage} and {@link TechStorePage} only handled the
 * protocol-relative {@code //host/...} form, so FullH4rd — which serves
 * {@code src="/img/productos/3/{slug}-0.jpg"} — wrote a bare path into
 * {@code productos.imagen_url} on every row it ever produced.</p>
 *
 * <p>An image that cannot be resolved comes back as {@code ""}: an unfetchable
 * path is not a weaker image, it is no image, and the pipeline already reads
 * empty as "no opinion" (CODE-5).</p>
 */
final class ImageUrl {

    private ImageUrl() {}

    /**
     * @param raw     the {@code src}/{@code data-src} exactly as the markup had it
     * @param baseUrl the site's base URL, used only for the relative forms
     * @return an absolute {@code http(s)} URL, or {@code ""} when there is none
     */
    static String absolutize(String raw, String baseUrl) {
        if (raw == null) return "";
        String src = raw.trim();
        if (src.isEmpty()) return "";

        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;

        // Everything left is relative and needs an origin to mean anything.
        if (baseUrl == null || baseUrl.isBlank()) return "";
        return baseUrl.replaceAll("/+$", "") + "/" + src.replaceAll("^/+", "");
    }
}
