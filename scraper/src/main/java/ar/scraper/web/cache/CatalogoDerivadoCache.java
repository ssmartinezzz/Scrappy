package ar.scraper.web.cache;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.ProductGroup;
import ar.scraper.config.CacheNames;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Views derived from the in-memory snapshot, cached per snapshot version.
 *
 * <p>Only anonymous, catalog-wide views belong here. Anything that reads the authenticated user
 * ({@code Sujeto}/{@code ActorResolver}: recommendations, outfits, PCs, supplements, favoritos) must
 * never be cached, or one user's answer is served to another.
 *
 * <p>A separate bean on purpose: the {@code *Endpoints} helpers are built with {@code new}, so
 * annotations on them do nothing, and a self-invocation would bypass the proxy. Callers read the
 * version BEFORE calling, so an entry is never staler than its key.
 */
@Component
public class CatalogoDerivadoCache {

    private final CatalogSnapshotPort snapshot;
    private final GroupingService grouping;

    public CatalogoDerivadoCache(CatalogSnapshotPort snapshot, GroupingService grouping) {
        this.snapshot = snapshot;
        this.grouping = grouping;
    }

    /**
     * {@code q}, {@code categoria} and {@code rubro} are lower-cased and blank-normalized, not
     * trimmed: the filters match case-insensitively but compare the raw text otherwise.
     */
    public record GruposKey(long ver, String q, String categoria, String rubro, boolean soloMulti) {
        public static GruposKey de(long ver, String q, String categoria, String rubro, boolean soloMulti) {
            return new GruposKey(ver, normalizar(q), normalizar(categoria), normalizar(rubro), soloMulti);
        }
    }

    /**
     * The grouped catalog BEFORE the {@code sitio} filter and paging. Empty when there is no
     * snapshot: {@code sync=true} forbids {@code unless}, and the entry cannot outlive the
     * snapshot it was keyed under because loading one bumps the version.
     */
    @Cacheable(cacheNames = CacheNames.GRUPOS, key = "#k", sync = true)
    public List<ProductGroup> grupos(GruposKey k) {
        var r = snapshot.getLastResult();
        if (r == null) return List.of();

        var filtrados = r.productos().stream()
            .filter(p -> k.q().isEmpty()
                || p.nombre().toLowerCase().contains(k.q())
                || (p.marca() != null && p.marca().toLowerCase().contains(k.q())))
            .filter(p -> k.categoria().isEmpty()
                || (p.categoria() != null && p.categoria().equalsIgnoreCase(k.categoria())))
            .filter(p -> k.rubro().isEmpty()
                || (p.rubro() != null && p.rubro().equalsIgnoreCase(k.rubro())))
            .toList();
        return List.copyOf(grouping.agrupar(filtrados, k.soloMulti()));
    }

    private static String normalizar(String s) {
        return StringUtils.isBlank(s) ? "" : s.toLowerCase();
    }
}
