package ar.scraper.config;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

    private static AnnotationConfigApplicationContext contexto(Map<String, Object> env) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", env));
        ctx.register(CacheConfig.class);
        ctx.refresh();
        return ctx;
    }

    private static Cache<Object, Object> nativo(CacheManager manager, String nombre) {
        return ((CaffeineCache) manager.getCache(nombre)).getNativeCache();
    }

    @Test
    void registersTheThreeSnapshotCaches() {
        try (var ctx = contexto(Map.of())) {
            assertThat(ctx.getBean(CacheManager.class).getCacheNames())
                    .containsExactlyInAnyOrder(CacheNames.GRUPOS, CacheNames.MARCAS, CacheNames.MEJORES);
        }
    }

    @Test
    void grupoCacheIsBoundedByTheConfiguredSize() {
        try (var ctx = contexto(Map.of("APP_CACHE_GRUPOS_MAX", "5"))) {
            var cache = ctx.getBean(CacheManager.class).getCache(CacheNames.GRUPOS);
            for (int i = 0; i < 200; i++) cache.put("k" + i, "v" + i);
            var nativo = nativo(ctx.getBean(CacheManager.class), CacheNames.GRUPOS);
            nativo.cleanUp();
            assertThat(nativo.estimatedSize()).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void defaultsMatchTheDocumentedSizes() {
        try (var ctx = contexto(Map.of())) {
            var manager = ctx.getBean(CacheManager.class);
            assertThat(nativo(manager, CacheNames.GRUPOS).policy().eviction().orElseThrow().getMaximum())
                    .isEqualTo(64);
            assertThat(nativo(manager, CacheNames.MARCAS).policy().eviction().orElseThrow().getMaximum())
                    .isEqualTo(64);
            assertThat(nativo(manager, CacheNames.MEJORES).policy().eviction().orElseThrow().getMaximum())
                    .isEqualTo(16);
        }
    }

    @Test
    void ttlIsOverridableFromTheEnvironment() {
        try (var ctx = contexto(Map.of("APP_CACHE_MEJORES_TTL_MINUTES", "7"))) {
            var expira = nativo(ctx.getBean(CacheManager.class), CacheNames.MEJORES)
                    .policy().expireAfterWrite().orElseThrow();
            assertThat(expira.getExpiresAfter().toMinutes()).isEqualTo(7);
        }
    }

    @Test
    void recordsStatsSoEvictionCanLogTheHitRatio() {
        try (var ctx = contexto(Map.of())) {
            var manager = ctx.getBean(CacheManager.class);
            manager.getCache(CacheNames.MARCAS).put("a", "b");
            manager.getCache(CacheNames.MARCAS).get("a");
            manager.getCache(CacheNames.MARCAS).get("nope");
            var stats = nativo(manager, CacheNames.MARCAS).stats();
            assertThat(stats.hitCount()).isEqualTo(1);
            assertThat(stats.missCount()).isEqualTo(1);
        }
    }

    @Test
    void nullIsNotCacheable() {
        try (var ctx = contexto(Map.of())) {
            var cache = ctx.getBean(CacheManager.class).getCache(CacheNames.MEJORES);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> cache.put("k", null));
        }
    }
}
