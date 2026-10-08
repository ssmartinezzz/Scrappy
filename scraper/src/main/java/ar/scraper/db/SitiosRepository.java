package ar.scraper.db;

import ar.scraper.classification.SitiosPort;
import ar.scraper.classification.SiteClassification;
import ar.scraper.classification.SiteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code 'VCP'}, {@code 'vcp'} and {@code 'Vcp'} are the same site, and {@code productos.sitio_key}
 * is a generated column carrying {@code sitioKey()}'s normalization.
 */
@Repository
class SitiosRepository implements SitiosPort {

    private static final Logger LOG = LoggerFactory.getLogger(SitiosRepository.class);

    /**
     * Mirrors the CHECK domain on {@code sitio.plataforma} — an untrusted client value must not
     * abort the write.
     */
    static final Set<String> PLATAFORMAS_VALIDAS = Set.of(
            "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
            "monkyforce", "maximus", "fullh4rd", "compragamer",
            "qloud", "oscommerce", "inpro", "morashop", "prestashop");

    private final JdbcTemplate jdbc;
    private final SiteRegistry siteRegistry;

    SitiosRepository(DataSource dataSource, SiteRegistry siteRegistry) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.siteRegistry = siteRegistry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void guardarSitio(String nombre, String url, String plataforma) {
        Objects.requireNonNull(nombre, "nombre must not be null");
        String plataformaValida = PLATAFORMAS_VALIDAS.contains(plataforma) ? plataforma : "tiendanube";
        try {
            jdbc.update("""
                    INSERT INTO sitios_dinamicos (nombre, url, created_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(nombre) DO UPDATE SET url=excluded.url
                    """, ps -> {
                ps.setString(1, nombre);
                ps.setString(2, url);
                ps.setObject(3, Timestamps.now());
            });
            jdbc.update("""
                    INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
                    VALUES (?, ?, ?, false, NULL, 'dinamico')
                    ON CONFLICT (nombre) DO UPDATE SET plataforma = EXCLUDED.plataforma
                    """, ps -> {
                ps.setString(1, nombre);
                ps.setString(2, SiteClassification.sitioKey(nombre));
                ps.setString(3, plataformaValida);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando sitio: {}", e.getMessage());
            Sql.marcarRollback();
        }
        recargarTrasCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void eliminarSitio(String nombre) {
        try {
            jdbc.update("DELETE FROM sitios_dinamicos WHERE nombre=?", nombre);
            jdbc.update("UPDATE sitio SET origen = 'historico' WHERE nombre = ? AND origen = 'dinamico'", nombre);
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando sitio: {}", e.getMessage());
            Sql.marcarRollback();
        }
        recargarTrasCommit();
    }

    private void recargarTrasCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            siteRegistry.reload();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                siteRegistry.reload();
            }
        });
    }

    @Override
    public List<Map<String, String>> cargarSitiosDinamicos() {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            jdbc.query("""
                    SELECT d.nombre, d.url, COALESCE(s.plataforma, 'tiendanube') AS plataforma
                    FROM sitios_dinamicos d
                    LEFT JOIN sitio s ON s.nombre = d.nombre
                    ORDER BY d.created_at
                    """, rs -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("nombre",     rs.getString(1));
                row.put("url",        rs.getString(2));
                row.put("plataforma", rs.getString(3));
                result.add(row);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }
        return result;
    }
}
