package ar.scraper.aggregator.normalize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Sole reader of the {@code sitio} table (close-1nf-and-3nf-foundation
 * extension, design E1). Loaded once at startup, refreshed by
 * {@code POST}/{@code DELETE /api/sitios} — every other reader (
 * {@link RubroResolver}, {@code ScraperFactory}, {@code NormalizerService})
 * goes through this instead of a name-set copy of the same knowledge
 * ({@code CODE-6}).
 *
 * <p>A miss ABSTAINS rather than guesses ({@code CODE-5}): the same defaults
 * an unmatched name got before this table existed — {@code plataforma}
 * defaults to {@code "tiendanube"} (the fallback branch of
 * {@code ScraperFactory.crear}), {@code esPremium} to {@code false}, and
 * {@code rubroForzado} to {@code null} (no rule forces a rubro).</p>
 */
@Component
public final class SiteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SiteRegistry.class);

    /** Abstention defaults for a {@code sitioKey} the table has no row for. */
    private static final String ABSTENCION_PLATAFORMA = "tiendanube";

    public record Sitio(String nombre, String sitioKey, String plataforma,
                         boolean esPremium, String rubroForzado, String origen) {
    }

    private final DataSource dataSource;
    private volatile Map<String, Sitio> porSitioKey = Map.of();

    @Autowired
    public SiteRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
        reload();
    }

    /** Test-only: seed the cache directly, no DB connection involved. */
    private SiteRegistry(Map<String, Sitio> seed) {
        this.dataSource = null;
        this.porSitioKey = Map.copyOf(seed);
    }

    /** Builds a registry over an already-resolved cache — classpath-only tests, no DB. */
    public static SiteRegistry forTesting(Map<String, Sitio> seed) {
        return new SiteRegistry(seed);
    }

    /**
     * Re-reads {@code sitio} in full and swaps the cache atomically. Called at
     * construction time and again whenever {@code POST}/{@code DELETE
     * /api/sitios} changes the table, so a dashboard-added or -removed site is
     * visible without a restart. A transient read failure keeps the previous
     * cache rather than wiping it (same "don't degrade what already worked"
     * posture as the rest of the read paths in this class's callers).
     */
    public void reload() {
        if (dataSource == null) return;
        Map<String, Sitio> next = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen FROM sitio")) {
            while (rs.next()) {
                String key = rs.getString("sitio_key");
                next.put(key, new Sitio(
                        rs.getString("nombre"), key, rs.getString("plataforma"),
                        rs.getBoolean("es_premium"), rs.getString("rubro_forzado"), rs.getString("origen")));
            }
            this.porSitioKey = Map.copyOf(next);
        } catch (Exception e) {
            LOG.warn("[SiteRegistry] Error cargando sitio: {}", e.getMessage());
        }
    }

    /** Sole lookup. A miss returns the abstention defaults, never a guess. */
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
