package ar.scraper.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Bounded in-memory caches for views derived from the in-memory catalog snapshot (see
 * {@code web.cache.CatalogoDerivadoCache}). Every cache has a size cap and a TTL; the TTL is a
 * backstop, because entries are also keyed by the snapshot version and evicted on each change.
 * Sizes and TTLs come from env like the rest of the config.
 *
 * <p>Class-based proxies on purpose, same as {@code TransactionConfig}: the cached beans are classes,
 * not interfaces. Null is never cached ({@code allowNullValues=false}); cached methods opt out with
 * {@code unless = "#result == null"}.
 */
@Configuration
@EnableCaching(proxyTargetClass = true)
class CacheConfig {

    @Bean
    CacheManager cacheManager(
            @Value("${APP_CACHE_GRUPOS_MAX:64}") long gruposMax,
            @Value("${APP_CACHE_GRUPOS_TTL_MINUTES:30}") long gruposTtl,
            @Value("${APP_CACHE_MARCAS_MAX:64}") long marcasMax,
            @Value("${APP_CACHE_MARCAS_TTL_MINUTES:30}") long marcasTtl,
            @Value("${APP_CACHE_MEJORES_MAX:16}") long mejoresMax,
            @Value("${APP_CACHE_MEJORES_TTL_MINUTES:30}") long mejoresTtl) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.registerCustomCache(CacheNames.GRUPOS, acotado(gruposMax, gruposTtl));
        manager.registerCustomCache(CacheNames.MARCAS, acotado(marcasMax, marcasTtl));
        manager.registerCustomCache(CacheNames.MEJORES, acotado(mejoresMax, mejoresTtl));
        return manager;
    }

    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> acotado(long max, long ttlMinutes) {
        return Caffeine.newBuilder()
                .maximumSize(max)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .recordStats()
                .build();
    }
}
