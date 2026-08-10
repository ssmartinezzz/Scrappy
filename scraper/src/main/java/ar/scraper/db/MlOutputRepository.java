package ar.scraper.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Persistence for the {@code ml_output} aggregate (last pipeline payload).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 */
class MlOutputRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MlOutputRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    MlOutputRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarMlOutput(JsonNode mlOutput) {
        if (mlOutput == null) return;
        if (!esMlOutputValido(mlOutput)) {
            LOG.debug("[DB] ML output inválido (sin scores/tendencias) — no se persiste");
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String json = MAPPER.writeValueAsString(mlOutput);
                String now  = LocalDateTime.now().format(DT);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ml_output (payload, created_at) VALUES (?, ?)")) {
                    ps.setString(1, json);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }
                // Mantener solo los últimos 10 outputs
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("""
                        DELETE FROM ml_output WHERE id NOT IN (
                            SELECT id FROM ml_output ORDER BY id DESC LIMIT 10
                        )""");
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
        }
    }

    /**
     * VALID == tiene un nodo {@code scores} objeto no vacío Y un nodo {@code tendencias}
     * presente como objeto. Mismo criterio usado por {@code MlEndpoints.tendencias()}
     * (R1/R3) — garantiza que todo lo que se persiste, se puede servir.
     */
    private boolean esMlOutputValido(JsonNode ml) {
        if (ml == null || ml.isNull() || !ml.isObject()) return false;
        JsonNode scores = ml.path("scores");
        if (!scores.isObject() || scores.isEmpty()) return false;
        JsonNode tend = ml.path("tendencias");
        return tend.isObject();
    }

    JsonNode cargarMlOutput() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT payload FROM ml_output ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                return MAPPER.readTree(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando ML output: {}", e.getMessage());
        }
        return null;
    }

    void limpiarMlOutput() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("DELETE FROM ml_output");
                c.commit();
                LOG.info("[DB] Datos ML eliminados.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
