package ar.scraper.web.cache;

import ar.scraper.catalog.CatalogoActualizado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;

/** Empties every snapshot-derived cache when the catalog readers are served changes. */
@Component
class CatalogCacheEvictor {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogCacheEvictor.class);

    private final CacheManager cacheManager;

    CatalogCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @EventListener
    void alCambiarElCatalogo(CatalogoActualizado evento) {
        var resumen = new StringJoiner(", ");
        for (String nombre : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(nombre);
            if (cache == null) continue;
            if (cache instanceof CaffeineCache caffeine) {
                var stats = caffeine.getNativeCache().stats();
                resumen.add("%s hit=%.0f%% (%d/%d)".formatted(nombre, stats.hitRate() * 100,
                        stats.hitCount(), stats.requestCount()));
            }
            cache.clear();
        }
        LOG.info("[CACHE] catalogo v{} cambio, caches vaciadas: {}", evento.version(), resumen);
    }
}
