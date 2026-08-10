package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persistence for the {@code categoria_stats} aggregate (per-category price
 * stats backing the trends panel).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 */
class CategoriaStatsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CategoriaStatsRepository.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    CategoriaStatsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        if (statsNode == null) return;
        String now = LocalDateTime.now().format(DT);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                var it = statsNode.fields();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO categoria_stats (categoria, payload, updated_at) VALUES (?,?,?) " +
                        "ON CONFLICT(categoria) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at")) {
                    while (it.hasNext()) {
                        var entry = it.next();
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue().toString());
                        ps.setString(3, now);
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

    java.util.Map<String, String> cargarCategoriaStats() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria, payload FROM categoria_stats ORDER BY categoria")) {
            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria_stats: {}", e.getMessage());
        }
        return result;
    }
}
