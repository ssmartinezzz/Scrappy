package ar.scraper.db;

import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.classification.CategoryGroups;
import ar.scraper.catalog.CategoriaStats;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keys not in {@link CategoryGroups#canonicalCategories()} are filtered out before binding — never
 * sent to the database at all — so one stray key from the pipeline cannot trip the FK and roll back
 * the whole run's stats.
 */
@Repository
class CategoriaStatsRepository implements CategoriaStatsPort {

    private static final Logger LOG = LoggerFactory.getLogger(CategoriaStatsRepository.class);

    private final JdbcTemplate jdbc;

    CategoriaStatsRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void guardarCategoriaStats(JsonNode statsNode) {
        if (statsNode == null) return;
        Set<String> canonicas = CategoryGroups.canonicalCategories();
        java.time.OffsetDateTime now = Timestamps.now();
        try {
            var it = statsNode.fields();
            while (it.hasNext()) {
                var entry = it.next();
                String categoria = entry.getKey();
                if (!canonicas.contains(categoria)) {
                    LOG.warn("[DB] categoria_stats: descartando clave no canónica '{}' " +
                            "(no está en CategoryGroups.canonicalCategories())", categoria);
                    continue;
                }
                JsonNode v = entry.getValue();
                jdbc.update(
                        "INSERT INTO categoria_stats " +
                        "(categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low, fence_high, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(categoria) DO UPDATE SET " +
                        "n=excluded.n, mean=excluded.mean, median=excluded.median, mode=excluded.mode, " +
                        "std=excluded.std, cv=excluded.cv, q1=excluded.q1, q3=excluded.q3, iqr=excluded.iqr, " +
                        "mad=excluded.mad, fence_low=excluded.fence_low, fence_high=excluded.fence_high, " +
                        "updated_at=excluded.updated_at",
                        ps -> {
                            ps.setString(1, categoria);
                            ps.setInt(2, v.path("n").asInt(0));
                            ps.setLong(3, v.path("mean").asLong(0));
                            ps.setLong(4, v.path("median").asLong(0));
                            ps.setLong(5, v.path("mode").asLong(0));
                            ps.setLong(6, v.path("std").asLong(0));
                            ps.setDouble(7, v.path("cv").asDouble(0));
                            ps.setLong(8, v.path("q1").asLong(0));
                            ps.setLong(9, v.path("q3").asLong(0));
                            ps.setLong(10, v.path("iqr").asLong(0));
                            ps.setLong(11, v.path("mad").asLong(0));
                            ps.setLong(12, v.path("fence_low").asLong(0));
                            ps.setLong(13, v.path("fence_high").asLong(0));
                            ps.setObject(14, now);
                        });
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
            Sql.marcarRollback();
        }
    }

    @Override
    public Map<String, CategoriaStats> cargarCategoriaStats() {
        Map<String, CategoriaStats> result = new LinkedHashMap<>();
        try {
            jdbc.query(
                "SELECT categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low, fence_high " +
                "FROM categoria_stats ORDER BY categoria",
                rs -> {
                    result.put(rs.getString("categoria"), new CategoriaStats(
                            rs.getInt("n"), rs.getLong("mean"), rs.getLong("median"), rs.getLong("mode"),
                            rs.getLong("std"), rs.getDouble("cv"), rs.getLong("q1"), rs.getLong("q3"),
                            rs.getLong("iqr"), rs.getLong("mad"), rs.getLong("fence_low"), rs.getLong("fence_high")));
                });
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria_stats: {}", e.getMessage());
        }
        return result;
    }
}
