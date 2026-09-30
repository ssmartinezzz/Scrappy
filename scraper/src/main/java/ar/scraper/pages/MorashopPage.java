package ar.scraper.pages;

import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * Morashop is a genuine Tiendanube store (LS.store.id 2268228, morashop2.mitiendanube.com) and the
 * shared extractor reads its cards without a single change — verified by running
 * {@code buildExtractorJs()} verbatim against {@code /suplementos/proteinas/}, which returned 50
 * clean products with name, price, compare-at, absolute URL and image.
 */
public class MorashopPage extends TiendanubePage {

    private final String seccionUrl;

    public MorashopPage(Page page, int timeoutMs, String sitio, String baseUrl,
                        double precioMin, double precioMax, List<String> extraUrls,
                        int maxPaginas) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls, maxPaginas);
        this.seccionUrl = baseUrl;
    }

    /** Off by correctness, not by speed — see the class javadoc. */
    @Override
    protected boolean usaApi() {
        return false;
    }

    @Override
    protected List<String> catalogoUrls() {
        navigateTo(seccionUrl);
        List<String> hojas = hojasOFalla(harvestHrefs());
        log.debug("[morashop] {} categorias hoja descubiertas bajo {}", hojas.size(), seccionUrl);
        return hojas;
    }

    /**
     * Separado de {@link #catalogoUrls()} para que sea testeable sin browser: el throw es la
     * propiedad de seguridad más importante de esta clase —lo que impide que un landing que cambió
     * se vea igual que una tienda vacía— y un throw que nadie vio dispararse no está verificado.
     */
    List<String> hojasOFalla(List<String> hrefs) {
        List<String> hojas = hojasDeCategoria(hrefs, seccionUrl);
        if (hojas.isEmpty()) throw new MorashopDiscoveryException(seccionUrl);
        return hojas;
    }

    @SuppressWarnings("unchecked")
    private List<String> harvestHrefs() {
        Object raw = page.evaluate(
                "Array.from(document.querySelectorAll('a[href]'))"
                        + ".map(function(a){return a.getAttribute('href');})");
        return raw instanceof List ? (List<String>) raw : List.of();
    }

    /**
     * Query strings and fragments are stripped, paths are absolutised against the section's own
     * origin, and duplicates collapse while preserving the landing's order..
     */
    static List<String> hojasDeCategoria(List<String> hrefs, String seccionUrl) {
        URI seccion = URI.create(seccionUrl);
        String host = seccion.getHost();
        String origen = seccion.getScheme() + "://" + host;
        String seccionPath = conBarras(seccion.getPath());

        LinkedHashSet<String> hojas = new LinkedHashSet<>();
        if (hrefs == null) return List.of();

        for (String href : hrefs) {
            if (StringUtils.isBlank(href)) continue;
            String h = sinQueryNiFragmento(href.trim());
            if (h.isBlank()) continue;

            String path;
            if (h.startsWith("http://") || h.startsWith("https://")) {
                URI u;
                try {
                    u = URI.create(h);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (u.getHost() == null || !u.getHost().equalsIgnoreCase(host)) continue;
                path = u.getPath();
            } else if (h.startsWith("/") && !h.startsWith("//")) {
                path = h;
            } else {
                // protocol-relative, scheme-less or genuinely relative — not worth guessing at, and
                // the landing does not need it.
                continue;
            }

            path = conBarras(path);
            if (!path.startsWith(seccionPath) || path.equals(seccionPath)) continue;

            String resto = path.substring(seccionPath.length());
            if (resto.chars().filter(c -> c == '/').count() != 1) continue;

            hojas.add(origen + path);
        }
        return List.copyOf(hojas);
    }

    private static String sinQueryNiFragmento(String h) {
        int q = h.indexOf('?');
        if (q >= 0) h = h.substring(0, q);
        int f = h.indexOf('#');
        if (f >= 0) h = h.substring(0, f);
        return h;
    }

    private static String conBarras(String path) {
        if (StringUtils.isBlank(path)) return "/";
        String s = path.trim();
        if (!s.startsWith("/")) s = "/" + s;
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }
}
