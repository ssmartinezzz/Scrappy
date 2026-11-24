package ar.scraper.pages;

import org.apache.commons.lang3.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * The identity of a Tiendanube product is its URL, and a theme appends where the shopper came
 * from ({@code entry_path}, {@code entry_category_id}) to the links of a category listing. The
 * same product then reaches the catalog under one URL per category it was listed in.
 *
 * <p>V136 applies this same rewrite in SQL to the rows already stored; both must agree on every
 * input ({@code TiendanubeUrlCanonicalTest} runs them on the same cases).
 */
public final class TiendanubeUrl {

    static final Set<String> PARAMS_DE_ORIGEN = Set.of("entry_path", "entry_category_id");

    private TiendanubeUrl() {
    }

    /** Drops only the entry params; every other param keeps its order and the fragment stays. */
    public static String canonica(String url) {
        if (StringUtils.isBlank(url)) return url;
        int hash = url.indexOf('#');
        String fragmento = hash < 0 ? "" : url.substring(hash);
        String cabeza = hash < 0 ? url : url.substring(0, hash);
        int q = cabeza.indexOf('?');
        if (q < 0) return url;

        String[] segmentos = cabeza.substring(q + 1).split("&", -1);
        if (Arrays.stream(segmentos).noneMatch(TiendanubeUrl::esDeOrigen)) return url;

        String query = Arrays.stream(segmentos).filter(s -> !esDeOrigen(s)).collect(Collectors.joining("&"));
        return cabeza.substring(0, q) + (query.isEmpty() ? "" : "?" + query) + fragmento;
    }

    private static boolean esDeOrigen(String segmento) {
        return PARAMS_DE_ORIGEN.contains(StringUtils.substringBefore(segmento, "="));
    }
}
