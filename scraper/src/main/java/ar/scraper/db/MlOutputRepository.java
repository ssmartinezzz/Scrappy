package ar.scraper.db;

import ar.scraper.ml.MlOutputPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

@Repository
class MlOutputRepository implements MlOutputPort {

    private static final Logger LOG = LoggerFactory.getLogger(MlOutputRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    MlOutputRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void guardarMlOutput(JsonNode mlOutput) {
        if (mlOutput == null) return;
        if (!esMlOutputValido(mlOutput)) {
            LOG.debug("[DB] ML output inválido (sin scores/tendencias) — no se persiste");
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(mlOutput);
            java.time.OffsetDateTime now = Timestamps.now();
            jdbc.update("INSERT INTO ml_output (payload, created_at) VALUES (?, ?)", ps -> {
                ps.setString(1, json);
                ps.setObject(2, now);
            });
            // Mantener solo los últimos 10 outputs
            jdbc.update("""
                    DELETE FROM ml_output WHERE id NOT IN (
                        SELECT id FROM ml_output ORDER BY id DESC LIMIT 10
                    )""");
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
            Sql.marcarRollback();
        }
    }

    private boolean esMlOutputValido(JsonNode ml) {
        if (ml == null || ml.isNull() || !ml.isObject()) return false;
        JsonNode scores = ml.path("scores");
        if (!scores.isObject() || scores.isEmpty()) return false;
        JsonNode tend = ml.path("tendencias");
        return tend.isObject();
    }

    @Override
    public JsonNode cargarMlOutput() {
        try {
            String payload = jdbc.query("SELECT payload FROM ml_output ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (payload != null) {
                return MAPPER.readTree(payload);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando ML output: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void limpiarMlOutput() {
        Sql.traducir(() -> {
            jdbc.update("DELETE FROM ml_output");
            LOG.info("[DB] Datos ML eliminados.");
        });
    }
}
