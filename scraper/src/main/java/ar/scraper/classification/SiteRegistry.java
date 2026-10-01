package ar.scraper.classification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class SiteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SiteRegistry.class);

    private static final String ABSTENCION_PLATAFORMA = "tiendanube";

    public record Sitio(String nombre, String sitioKey, String plataforma,
                         boolean esPremium, String rubroForzado, String origen) {
    }

    private final SiteSource source;
    private volatile Map<String, Sitio> porSitioKey = Map.of();

    public SiteRegistry(SiteSource source) {
        this.source = source;
        reload();
    }

    /** Builds a registry over an already-resolved cache — classpath-only tests, no DB. */
    public static SiteRegistry forTesting(Map<String, Sitio> seed) {
        Map<String, Sitio> copia = Map.copyOf(seed);
        return new SiteRegistry(() -> copia);
    }

    /** Re-reads {@code sitio} in full and swaps the cache atomically. */
    public void reload() {
        try {
            this.porSitioKey = Map.copyOf(source.cargar());
        } catch (RuntimeException e) {
            LOG.warn("[SiteRegistry] Error cargando sitio: {}", e.getMessage());
        }
    }

    /** A miss returns the abstention defaults, never a guess. */
    public Sitio porKey(String sitioKey) {
        Sitio found = porSitioKey.get(sitioKey);
        if (found != null) return found;
        return new Sitio(sitioKey, sitioKey, ABSTENCION_PLATAFORMA, false, null, "historico");
    }

    public String plataforma(String sitioKey) {
        return porKey(sitioKey).plataforma();
    }

    public boolean esPremium(String sitioKey) {
        return porKey(sitioKey).esPremium();
    }

    public String rubroForzado(String sitioKey) {
        return porKey(sitioKey).rubroForzado();
    }
}
