package ar.scraper.db;

import ar.scraper.aggregator.normalize.CategoryGroups;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persistence for the {@code categoria_stats} aggregate (per-category price
 * stats backing the trends panel).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). Flattened
 * from a {@code payload TEXT} JSON blob to 12 typed columns with a real FK to
 * {@code categoria(nombre)} by V16 (close-1nf-and-3nf-foundation, design
 * DD6).</p>
 *
 * <p>Keys not in {@link CategoryGroups#canonicalCategories()} are filtered
 * out <b>before</b> binding — never sent to the database at all — so one
 * stray key from the pipeline cannot trip the FK and roll back the whole
 * run's stats. That is the project's known swallowed-SQL-error failure mode
 * (the upsert, one table over) turned into a named, per-key {@code WARN}
 * instead of a silent total loss.</p>
 */
class CategoriaStatsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CategoriaStatsRepository.class);

    private final DataSource dataSource;

    CategoriaStatsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarCategoriaStats(JsonNode statsNode) {
        if (statsNode == null) return;
        Set<String> canonicas = CategoryGroups.canonicalCategories();
        java.time.OffsetDateTime now = Timestamps.now();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                var it = statsNode.fields();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO categoria_stats " +
                        "(categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low, fence_high, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(categoria) DO UPDATE SET " +
                        "n=excluded.n, mean=excluded.mean, median=excluded.median, mode=excluded.mode, " +
                        "std=excluded.std, cv=excluded.cv, q1=excluded.q1, q3=excluded.q3, iqr=excluded.iqr, " +
                        "mad=excluded.mad, fence_low=excluded.fence_low, fence_high=excluded.fence_high, " +
                        "updated_at=excluded.updated_at")) {
                    while (it.hasNext()) {
                        var entry = it.next();
                        String categoria = entry.getKey();
                        if (!canonicas.contains(categoria)) {
                            LOG.warn("[DB] categoria_stats: descartando clave no canónica '{}' " +
                                    "(no está en CategoryGroups.canonicalCategories())", categoria);
                            continue;
                        }
                        JsonNode v = entry.getValue();
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
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
        }
    }

    Map<String, CategoriaStats> cargarCategoriaStats() {
        Map<String, CategoriaStats> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low, fence_high " +
                "FROM categoria_stats ORDER BY categoria")) {
            while (rs.next()) {
                result.put(rs.getString("categoria"), new CategoriaStats(
                        rs.getInt("n"), rs.getLong("mean"), rs.getLong("median"), rs.getLong("mode"),
                        rs.getLong("std"), rs.getDouble("cv"), rs.getLong("q1"), rs.getLong("q3"),
                        rs.getLong("iqr"), rs.getLong("mad"), rs.getLong("fence_low"), rs.getLong("fence_high")));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria_stats: {}", e.getMessage());
        }
        return result;
    }
}
