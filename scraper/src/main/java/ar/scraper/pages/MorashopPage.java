package ar.scraper.pages;

import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Morashop-specific Tiendanube page (add-morashop-and-fix-entreno-pagination).
 *
 * <p>Morashop is a genuine Tiendanube store (LS.store.id 2268228,
 * morashop2.mitiendanube.com) and the shared extractor reads its cards without
 * a single change — verified by running {@code buildExtractorJs()} verbatim
 * against {@code /suplementos/proteinas/}, which returned 50 clean products
 * with name, price, compare-at, absolute URL and image. So this subclass
 * specialises NAVIGATION only, never extraction, the same way
 * {@link MonkyforcePage} specialises the name selector only.
 *
 * <p>Two things make it different from every other TN site here:
 *
 * <p><b>It has no catalogue URL.</b> {@code /productos/} is a themed
 * "8 CATEGORÍAS" landing carrying zero products, and {@code /suplementos/} is
 * an index of subcategories, also zero. Products exist only in the leaf
 * categories one level below, so {@link #catalogoUrls()} discovers those
 * leaves instead of trusting a single URL. Discovery rather than a hardcoded
 * list because a thirteenth category would otherwise be missed in silence.
 *
 * <p><b>It is multi-rubro.</b> Alongside supplements it sells supermercado,
 * electro-hogar and bodega, none of which maps to a value in the {@code rubro}
 * domain. Only the {@code /suplementos/} section is in scope, which is why
 * {@link #usaApi()} is false: the Tiendanube products API returns the WHOLE
 * store with no section filter, so if that endpoint were ever enabled it would
 * quietly import all four rubros.
 */
public class MorashopPage extends TiendanubePage {

    /** Landing whose leaves are the real catalogue. Also the page's {@code baseUrl}. */
    private final String seccionUrl;

    public MorashopPage(Page page, int timeoutMs, String sitio, String baseUrl,
                        double precioMin, double precioMax, List<String> extraUrls,
                        int maxPaginas) {
        super(page, timeoutMs, sitio, baseUrl, precioMin, precioMax, extraUrls, maxPaginas);
        this.seccionUrl = baseUrl;
    }

    /**
     * The products API returns the whole store, unfiltered by section. Off by
     * correctness, not by speed — see the class javadoc.
     */
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
     * Aplica {@link #hojasDeCategoria} y convierte "no encontré nada" en un
     * error explícito. Separado de {@link #catalogoUrls()} para que sea
     * testeable sin browser: el throw es la propiedad de seguridad más
     * importante de esta clase —lo que impide que un landing que cambió se vea
     * igual que una tienda vacía— y un throw que nadie vio dispararse no está
     * verificado.
     */
    List<String> hojasOFalla(List<String> hrefs) {
        List<String> hojas = hojasDeCategoria(hrefs, seccionUrl);
        if (hojas.isEmpty()) throw new MorashopDiscoveryException(seccionUrl);
        return hojas;
    }

    /** The whole browser-dependent surface of discovery: one query, no rules. */
    @SuppressWarnings("unchecked")
    private List<String> harvestHrefs() {
        Object raw = page.evaluate(
                "Array.from(document.querySelectorAll('a[href]'))"
                        + ".map(function(a){return a.getAttribute('href');})");
        return raw instanceof List ? (List<String>) raw : List.of();
    }

    /**
     * Pure, browser-free, and therefore the part that carries the rules —
     * mirrors {@link TiendanubePage#resolveNextPageFromHrefs}.
     *
     * <p>A leaf is a same-host path exactly ONE segment below {@code seccionUrl}.
     * That single rule is what excludes the section index itself, the sibling
     * sections ({@code /supermercado/}, {@code /electro-hogar/},
     * {@code /bodega/}) that would drag in out-of-scope rubros, and any deeper
     * sub-subcategory. Query strings and fragments are stripped, paths are
     * absolutised against the section's own origin, and duplicates collapse
     * while preserving the landing's order.
     *
     * @return leaf URLs, possibly empty; deciding that empty is an error is
     *         {@link #catalogoUrls()}'s job, not this method's
     */
    static List<String> hojasDeCategoria(List<String> hrefs, String seccionUrl) {
        URI seccion = URI.create(seccionUrl);
        String host = seccion.getHost();
        String origen = seccion.getScheme() + "://" + host;
        String seccionPath = conBarras(seccion.getPath());

        LinkedHashSet<String> hojas = new LinkedHashSet<>();
        if (hrefs == null) return List.of();

        for (String href : hrefs) {
            if (href == null || href.isBlank()) continue;
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
                // protocol-relative, scheme-less or genuinely relative — not
                // worth guessing at, and the landing does not need it.
                continue;
            }

            path = conBarras(path);
            if (!path.startsWith(seccionPath) || path.equals(seccionPath)) continue;

            String resto = path.substring(seccionPath.length());
            // Exactly one segment below the section: "proteinas/" has a single
            // slash, "proteinas/veganas/" has two.
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
        if (path == null || path.isBlank()) return "/";
        String s = path.trim();
        if (!s.startsWith("/")) s = "/" + s;
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }
}
