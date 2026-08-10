package ar.scraper.db;

import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
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
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code cron_jobs} / {@code cron_executions} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). DatabaseService
 * keeps every public method and delegates here, so its ~55 test call sites and
 * the cron services see an unchanged surface.</p>
 *
 * <p>Ya no hay writeLock global: cada método toma su propia conexión pooled;
 * la correctitud concurrente la da Postgres MVCC (design D1), no un lock
 * de aplicación.</p>
 */
class CronRepository {

    private static final Logger LOG = LoggerFactory.getLogger(CronRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    CronRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── Cron Jobs ───────────────────────────────────────────────────────────

    long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cron_jobs
                        (name, precio_min, precio_max, sitios_json, force_retrain, use_gpu,
                         cron_expr, enabled, created_at, updated_at, next_run_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
            String now = LocalDateTime.now().format(DT);
            ps.setString(1, name);
            ps.setDouble(2, precioMin);
            ps.setDouble(3, precioMax);
            ps.setString(4, MAPPER.writeValueAsString(sitios != null ? sitios : List.of()));
            ps.setInt(5, forceRetrain ? 1 : 0);
            ps.setInt(6, useGpu ? 1 : 0);
            ps.setString(7, cronExpr);
            ps.setInt(8, enabled ? 1 : 0);
            ps.setString(9, now);
            ps.setString(10, now);
            ps.setString(11, nextRunAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron job: {}", e.getMessage());
            return -1;
        }
    }

    /** Retorna {@code false} sin persistir si {@code id} no existe. */
    boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE cron_jobs SET name=?, precio_min=?, precio_max=?, sitios_json=?,
                        force_retrain=?, use_gpu=?, cron_expr=?, enabled=?, updated_at=?, next_run_at=?
                    WHERE id=?
                    """)) {
            ps.setString(1, name);
            ps.setDouble(2, precioMin);
            ps.setDouble(3, precioMax);
            ps.setString(4, MAPPER.writeValueAsString(sitios != null ? sitios : List.of()));
            ps.setInt(5, forceRetrain ? 1 : 0);
            ps.setInt(6, useGpu ? 1 : 0);
            ps.setString(7, cronExpr);
            ps.setInt(8, enabled ? 1 : 0);
            ps.setString(9, LocalDateTime.now().format(DT));
            ps.setString(10, nextRunAt);
            ps.setLong(11, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron job {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Elimina el job y (cascada manual) sus ejecuciones. Retorna {@code false} si {@code id} no existía. */
    boolean deleteCronJob(long id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delExec = c.prepareStatement("DELETE FROM cron_executions WHERE job_id=?");
                 PreparedStatement delJob  = c.prepareStatement("DELETE FROM cron_jobs WHERE id=?")) {
                delExec.setLong(1, id);
                delExec.executeUpdate();
                delJob.setLong(1, id);
                int rows = delJob.executeUpdate();
                if (rows == 0) { c.rollback(); return false; }
                c.commit();
                return true;
            } catch (Exception e) {
                LOG.warn("[DB] Error eliminando cron job {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error eliminando cron job {}: {}", id, e.getMessage());
            return false;
        }
    }

    List<CronJob> listCronJobs() {
        List<CronJob> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id,name,precio_min,precio_max,sitios_json,force_retrain,use_gpu,cron_expr," +
                "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs ORDER BY id")) {
            while (rs.next()) result.add(cronJobDesdeFila(rs));
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron jobs: {}", e.getMessage());
        }
        return result;
    }

    Optional<CronJob> getCronJob(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,name,precio_min,precio_max,sitios_json,force_retrain,use_gpu,cron_expr," +
                "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(cronJobDesdeFila(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo cron job {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private CronJob cronJobDesdeFila(ResultSet rs) throws SQLException {
        List<String> sitios = List.of();
        try {
            JsonNode arr = MAPPER.readTree(rs.getString("sitios_json"));
            if (arr.isArray()) {
                List<String> s = new ArrayList<>();
                arr.forEach(n -> s.add(n.asText()));
                sitios = s;
            }
        } catch (Exception ignored) {}
        return new CronJob(
                rs.getLong("id"), rs.getString("name"),
                rs.getDouble("precio_min"), rs.getDouble("precio_max"), sitios,
                rs.getInt("force_retrain") == 1, rs.getInt("use_gpu") == 1,
                rs.getString("cron_expr"), rs.getInt("enabled") == 1,
                rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("last_run_at"), rs.getString("next_run_at"));
    }

    /** Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un run. */
    boolean touchLastRunAt(long jobId, String lastRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE cron_jobs SET last_run_at=? WHERE id=?")) {
            ps.setString(1, lastRunAt);
            ps.setLong(2, jobId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll. */
    boolean updateNextRunAt(long jobId, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE cron_jobs SET next_run_at=? WHERE id=?")) {
            ps.setString(1, nextRunAt);
            ps.setLong(2, jobId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando next_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    // ─── Cron Executions ────────────────────────────────────────────────────

    long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cron_executions (job_id, started_at, status, skipped_reason)
                    VALUES (?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, jobId);
            ps.setString(2, startedAt);
            ps.setString(3, status);
            ps.setString(4, skippedReason);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron execution (job {}): {}", jobId, e.getMessage());
            return -1;
        }
    }

    boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE cron_executions
                    SET finished_at=?, status=?, skipped_reason=?, log_output=?, duration_ms=?
                    WHERE id=?
                    """)) {
            ps.setString(1, finishedAt);
            ps.setString(2, status);
            ps.setString(3, skippedReason);
            ps.setString(4, logOutput);
            if (durationMs != null) ps.setInt(5, durationMs); else ps.setNull(5, Types.INTEGER);
            ps.setLong(6, execId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron execution {}: {}", execId, e.getMessage());
            return false;
        }
    }

    List<CronExecution> listExecutions(long jobId, int limit) {
        List<CronExecution> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                "FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, jobId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(cronExecutionDesdeFila(rs));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron executions (job {}): {}", jobId, e.getMessage());
        }
        return result;
    }

    Optional<CronExecution> getExecution(long execId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                "FROM cron_executions WHERE id=?")) {
            ps.setLong(1, execId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(cronExecutionDesdeFila(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo cron execution {}: {}", execId, e.getMessage());
            return Optional.empty();
        }
    }

    private CronExecution cronExecutionDesdeFila(ResultSet rs) throws SQLException {
        int durMs = rs.getInt("duration_ms");
        Integer duration = rs.wasNull() ? null : durMs;
        return new CronExecution(
                rs.getLong("id"), rs.getLong("job_id"),
                rs.getString("started_at"), rs.getString("finished_at"),
                rs.getString("status"), rs.getString("skipped_reason"),
                rs.getString("log_output"), duration);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    void pruneCronExecutions(long jobId, int keep) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM cron_executions WHERE job_id=? AND id NOT IN (
                        SELECT id FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?
                    )
                    """)) {
            ps.setLong(1, jobId);
            ps.setLong(2, jobId);
            ps.setInt(3, keep);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error pruning cron executions (job {}): {}", jobId, e.getMessage());
        }
    }
}
