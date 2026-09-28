package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.Facets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * `catalog-facets-perf`, T3 — caches {@code facetas()}/{@code resumen()} by
 * {@code (cota, catalog_version)}. {@code buscar()} is a pass-through: it
 * varies per filter/page, so caching it would just be an unbounded map.
 *
 * <p>Version is read BEFORE computing, so a cached entry can be fresher than
 * its label but never staler — a write that commits between the read and the
 * compute only makes the result MORE current than what it's keyed under. No
 * version row (migration not applied, or the row TRUNCATEd away with nothing
 * to reseed it) means don't cache, not "cache under a fake key".</p>
 *
 * <p>Single-flight per (kind, cota, version): concurrent callers for the same
 * key share one {@link CompletableFuture} instead of piling N queries onto the
 * pool. Bounded to {@value #MAX_ENTRIES} entries (LRU) — the key space is a
 * handful of {@code cota} values in practice, not the whole catalog history.</p>
 *
 * <p><b>Never caches a fallback.</b> {@code CatalogQueryRepository} swallows
 * SQL errors and returns an all-empty {@link Facets} / a {@link CatalogResumen}
 * with {@code total()==0} instead of throwing. Caching THAT under the current
 * version would serve an empty catalog until the next write bumps it — a
 * transient pool timeout turning into hours of empty facets / a 204. Waiting
 * single-flight callers still get the fallback (returning nothing would be
 * worse), but the entry is removed right after, so the next call recomputes.
 * The one tradeoff: a genuinely empty catalog looks identical and also isn't
 * cached — cheap, since there is nothing to scan.</p>
 */
@Repository
@Primary
class CachingCatalogQueryPort implements CatalogQueryPort {

    private static final Logger LOG = LoggerFactory.getLogger(CachingCatalogQueryPort.class);
    private static final int MAX_ENTRIES = 8;

    private final CatalogQueryPort delegate;
    private final DataSource dataSource;

    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    CachingCatalogQueryPort(@Qualifier("catalogQueryRepository") CatalogQueryPort delegate, DataSource dataSource) {
        this.delegate = delegate;
        this.dataSource = dataSource;
    }

    @Override
    public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size) {
        return delegate.buscar(filtro, orden, page, size);
    }

    @Override
    public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size, Optional<Instant> desde) {
        return delegate.buscar(filtro, orden, page, size, desde);
    }

    @Override
    public Facets facetas() {
        return facetas(Optional.empty());
    }

    @Override
    public Facets facetas(Optional<Instant> desde) {
        return conCache("facetas", desde, CachingCatalogQueryPort::esFallback, () -> delegate.facetas(desde));
    }

    @Override
    public CatalogResumen resumen() {
        return resumen(Optional.empty());
    }

    @Override
    public CatalogResumen resumen(Optional<Instant> desde) {
        return conCache("resumen", desde, r -> r.total() == 0, () -> delegate.resumen(desde));
    }

    /** La misma forma que devuelve {@code CatalogQueryRepository.facetas()} cuando la SQL falla. */
    private static boolean esFallback(Facets f) {
        return f.talles().isEmpty() && f.generos().isEmpty() && f.categorias().isEmpty()
                && f.marcas().isEmpty() && f.badges().isEmpty() && f.subCategorias().isEmpty()
                && f.fits().isEmpty() && f.estampados().isEmpty() && f.escotes().isEmpty()
                && f.colorDominantes().isEmpty();
    }

    private <T> T conCache(String kind, Optional<Instant> desde, Predicate<T> esFallback, Supplier<T> compute) {
        OptionalLong version = leerVersion();
        if (version.isEmpty()) return compute.get();
        return singleFlight(new CacheKey(kind, desde), version.getAsLong(), esFallback, compute);
    }

    @SuppressWarnings("unchecked")
    private <T> T singleFlight(CacheKey key, long version, Predicate<T> esFallback, Supplier<T> compute) {
        CompletableFuture<Object> future;
        boolean owner;
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.version() == version) {
                future = entry.future();
                owner = false;
            } else {
                future = new CompletableFuture<>();
                cache.put(key, new CacheEntry(version, future));
                owner = true;
            }
        }
        if (!owner) return (T) future.join();
        try {
            T result = compute.get();
            future.complete(result); // unblocks waiters first, fallback or not
            if (esFallback.test(result)) {
                synchronized (cache) {
                    cache.remove(key, new CacheEntry(version, future));
                }
            }
            return result;
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            synchronized (cache) {
                cache.remove(key, new CacheEntry(version, future));
            }
            throw e;
        }
    }

    private OptionalLong leerVersion() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT version FROM catalog_version");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
        } catch (Exception e) {
            LOG.warn("[DB] No se pudo leer catalog_version, sirviendo sin cache: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    private record CacheKey(String kind, Optional<Instant> desde) {
    }

    private record CacheEntry(long version, CompletableFuture<Object> future) {
    }
}
