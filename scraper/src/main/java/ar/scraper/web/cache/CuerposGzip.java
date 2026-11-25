package ar.scraper.web.cache;

import ar.scraper.catalog.CatalogoActualizado;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * The gzip bytes of a cached view, kept for as long as that view instance is alive. The key is the instance itself
 * (weakly, by identity), so the bytes cannot outlive the {@code @Cacheable} entry that holds the view: no second TTL
 * and no second version to go stale. A snapshot change drops them at once instead of waiting for the collector.
 */
@Component
public class CuerposGzip {

    private static final int MAX_VISTAS = 16;

    private final Cache<List<?>, byte[]> cuerpos = Caffeine.newBuilder()
            .weakKeys()
            .maximumSize(MAX_VISTAS)
            .build();

    /**
     * The gzip of {@code serializador}'s identity bytes for {@code vista}, computed once per instance even under
     * concurrent callers. A failing serializer caches nothing and propagates. The array is shared between callers:
     * treat it as read-only.
     */
    public byte[] de(List<?> vista, Supplier<byte[]> serializador) {
        return cuerpos.get(vista, v -> comprimir(serializador.get()));
    }

    @EventListener
    void alCambiarElCatalogo(CatalogoActualizado evento) {
        cuerpos.invalidateAll();
    }

    private static byte[] comprimir(byte[] plano) {
        var salida = new ByteArrayOutputStream(plano.length / 8 + 64);
        try (var gzip = new GZIPOutputStream(salida)) {
            gzip.write(plano);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return salida.toByteArray();
    }
}
