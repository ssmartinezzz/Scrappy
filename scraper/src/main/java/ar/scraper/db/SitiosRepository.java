package ar.scraper.db;

import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.aggregator.normalize.SiteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Persistence for the {@code sitios_dinamicos} aggregate (sites added from the
 * dashboard, on top of the ones declared in {@code config.properties}).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 *
 * <p>close-1nf-and-3nf-foundation extension (design E1/E7): {@code sitio} is
 * DD4's deferred write path — {@link #guardarSitio} upserts both tables so
 * the FK {@code productos.sitio_key -> sitio(sitio_key)} added by {@code V23}
 * can never fail for a name reachable through here. That FK targets the
 * normalized KEY, not the display name: {@code 'VCP'}, {@code 'vcp'} and
 * {@code 'Vcp'} are the same site, and {@code productos.sitio_key} is a
 * generated column carrying {@code sitioKey()}'s normalization. Its other half
 * is the get-or-create inside {@code sp_upsert_run}, which covers names that
 * arrive from a scrape without passing through this repository;
 * {@link #eliminarSitio} deletes the {@code sitios_dinamicos} row and flips
 * {@code sitio.origen} to {@code 'historico'} — the state {@code V18}
 * invented {@code origen} for. Every write calls
 * {@link SiteRegistry#reload()} so the cached copy never lags the table it
 * mirrors.</p>
 */
class SitiosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SitiosRepository.class);

    /**
     * Mirrors the CHECK domain on {@code sitio.plataforma} — an untrusted
     * client value must not abort the write. Package-private (not
     * {@code private}) so {@code PlatformVocabularySyncTest} can assert it
     * stays in sync with the CHECK, classpath-only, no DB.
     */
    static final Set<String> PLATAFORMAS_VALIDAS = Set.of(
            "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
            "monkyforce", "maximus", "fullh4rd", "compragamer",
            "qloud", "oscommerce");

    private final DataSource dataSource;
    private final SiteRegistry siteRegistry;

    SitiosRepository(DataSource dataSource, SiteRegistry siteRegistry) {
        this.dataSource = dataSource;
        this.siteRegistry = siteRegistry;
    }

    void guardarSitio(String nombre, String url, String plataforma) {
        Objects.requireNonNull(nombre, "nombre must not be null");
        // sitios_dinamicos.plataforma was dropped by V20 — sitio.plataforma
        // (below) is the only copy now (design E1). sitios_dinamicos keeps
        // (nombre, url, created_at): its remaining job is "the URL to scrape".
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO sitios_dinamicos (nombre, url, created_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(nombre) DO UPDATE SET url=excluded.url
                    """)) {
            ps.setString(1, nombre);
            ps.setString(2, url);
            ps.setObject(3, Timestamps.now());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando sitio: {}", e.getMessage());
        }

        String plataformaValida = PLATAFORMAS_VALIDAS.contains(plataforma) ? plataforma : "tiendanube";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
                    VALUES (?, ?, ?, false, NULL, 'dinamico')
                    ON CONFLICT (nombre) DO UPDATE SET plataforma = EXCLUDED.plataforma
                    """)) {
            ps.setString(1, nombre);
            ps.setString(2, SiteClassification.sitioKey(nombre));
            ps.setString(3, plataformaValida);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando sitio (tabla sitio): {}", e.getMessage());
        }

        siteRegistry.reload();
    }

    void eliminarSitio(String nombre) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sitios_dinamicos WHERE nombre=?")) {
            ps.setString(1, nombre);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando sitio: {}", e.getMessage());
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE sitio SET origen = 'historico' WHERE nombre = ? AND origen = 'dinamico'")) {
            ps.setString(1, nombre);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando origen de sitio: {}", e.getMessage());
        }

        siteRegistry.reload();
    }

    List<Map<String, String>> cargarSitiosDinamicos() {
        List<Map<String, String>> result = new ArrayList<>();
        // plataforma reads through sitio now (V20) — LEFT JOIN + COALESCE so a
        // dinamico row somehow missing its sitio counterpart still abstains to
        // the same default an unmatched name always got, rather than dropping
        // the row from the response.
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT d.nombre, d.url, COALESCE(s.plataforma, 'tiendanube') AS plataforma
                FROM sitios_dinamicos d
                LEFT JOIN sitio s ON s.nombre = d.nombre
                ORDER BY d.created_at
                """)) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("nombre",     rs.getString(1));
                row.put("url",        rs.getString(2));
                row.put("plataforma", rs.getString(3));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }
        return result;
    }
}
