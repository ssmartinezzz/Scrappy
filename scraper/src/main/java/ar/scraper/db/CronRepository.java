package ar.scraper.db;

import ar.scraper.scheduling.CronExecution;
import ar.scraper.scheduling.CronJob;
import ar.scraper.scheduling.CronPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

@Repository
class CronRepository implements CronPort {

    private static final Logger LOG = LoggerFactory.getLogger(CronRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    CronRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Job y lista de sitios se escriben juntos: un job sin sus sitios scrapearía todo el catálogo.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO cron_jobs
                            (name, precio_min, precio_max, force_retrain, use_gpu,
                             cron_expr, enabled, created_at, updated_at, next_run_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?::timestamptz)
                        """, new String[]{"id"});
                java.time.OffsetDateTime now = Timestamps.now();
                ps.setString(1, name);
                ps.setDouble(2, precioMin);
                ps.setDouble(3, precioMax);
                ps.setBoolean(4, forceRetrain);
                ps.setBoolean(5, useGpu);
                ps.setString(6, cronExpr);
                ps.setBoolean(7, enabled);
                ps.setObject(8, now);
                ps.setObject(9, now);
                ps.setString(10, nextRunAt);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) { Sql.marcarRollback(); return -1; }
            long id = key.longValue();
            reemplazarSitios(id, sitios);
            return id;
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron job: {}", e.getMessage());
            Sql.marcarRollback();
            return -1;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try {
            int filas = jdbc.update("""
                    UPDATE cron_jobs SET name=?, precio_min=?, precio_max=?,
                        force_retrain=?, use_gpu=?, cron_expr=?, enabled=?, updated_at=?, next_run_at=?::timestamptz
                    WHERE id=?
                    """, ps -> {
                ps.setString(1, name);
                ps.setDouble(2, precioMin);
                ps.setDouble(3, precioMax);
                ps.setBoolean(4, forceRetrain);
                ps.setBoolean(5, useGpu);
                ps.setString(6, cronExpr);
                ps.setBoolean(7, enabled);
                ps.setObject(8, Timestamps.now());
                ps.setString(9, nextRunAt);
                ps.setLong(10, id);
            });
            if (filas == 0) return false;
            reemplazarSitios(id, sitios);
            return true;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron job {}: {}", id, e.getMessage());
            Sql.marcarRollback();
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteCronJob(long id) {
        try {
            jdbc.update("DELETE FROM cron_executions WHERE job_id=?", id);
            if (jdbc.update("DELETE FROM cron_jobs WHERE id=?", id) == 0) { Sql.marcarRollback(); return false; }
            return true;
        } catch (DataAccessException e) {
            LOG.warn("[DB] Error eliminando cron job {}: {}", id, e.getMessage());
            Sql.marcarRollback();
            return false;
        }
    }

    @Override
    public List<CronJob> listCronJobs() {
        List<CronJob> result = new ArrayList<>();
        try {
            java.util.Map<Long, List<String>> sitiosPorJob = new java.util.HashMap<>();
            jdbc.query("SELECT job_id, sitio FROM cron_job_sitio ORDER BY job_id, posicion", rs -> {
                sitiosPorJob.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
            });
            jdbc.query("SELECT id,name,precio_min,precio_max,force_retrain,use_gpu,cron_expr," +
                       "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs ORDER BY id",
                    rs -> {
                        result.add(cronJobDesdeFila(rs,
                                sitiosPorJob.getOrDefault(rs.getLong("id"), List.of())));
                    });
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron jobs: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Optional<CronJob> getCronJob(long id) {
        try {
            Optional<CronJob> fila = jdbc.query(
                    "SELECT id,name,precio_min,precio_max,force_retrain,use_gpu,cron_expr," +
                    "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs WHERE id=?",
                    ps -> ps.setLong(1, id),
                    rs -> rs.next() ? Optional.of(cronJobDesdeFila(rs, List.of())) : Optional.<CronJob>empty());
            if (fila.isEmpty()) return fila;
            CronJob j = fila.get();
            return Optional.of(new CronJob(j.id(), j.name(), j.precioMin(), j.precioMax(), sitiosDe(id),
                    j.forceRetrain(), j.useGpu(), j.cronExpr(), j.enabled(),
                    j.createdAt(), j.updatedAt(), j.lastRunAt(), j.nextRunAt()));
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo cron job {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** Los sitios de UN job, en orden. */
    private List<String> sitiosDe(long jobId) {
        List<String> sitios = new ArrayList<>();
        jdbc.query("SELECT sitio FROM cron_job_sitio WHERE job_id=? ORDER BY posicion",
                ps -> ps.setLong(1, jobId),
                rs -> {
                    sitios.add(rs.getString(1));
                });
        return sitios;
    }

    private void reemplazarSitios(long jobId, List<String> sitios) {
        jdbc.update("DELETE FROM cron_job_sitio WHERE job_id=?", jobId);
        if (sitios == null || sitios.isEmpty()) return;
        List<String> noVacios = sitios.stream().filter(StringUtils::isNotBlank).toList();
        if (noVacios.isEmpty()) return;
        short[] posicion = {1};
        jdbc.batchUpdate("INSERT INTO cron_job_sitio (job_id, posicion, sitio) VALUES (?,?,?)",
                noVacios, noVacios.size(), (ps, sitio) -> {
                    ps.setLong(1, jobId);
                    ps.setShort(2, posicion[0]++);
                    ps.setString(3, sitio);
                });
    }

    private CronJob cronJobDesdeFila(ResultSet rs, List<String> sitios) throws SQLException {
        return new CronJob(
                rs.getLong("id"), rs.getString("name"),
                rs.getDouble("precio_min"), rs.getDouble("precio_max"), sitios,
                rs.getBoolean("force_retrain"), rs.getBoolean("use_gpu"),
                rs.getString("cron_expr"), rs.getBoolean("enabled"),
                Timestamps.iso(rs, "created_at"), Timestamps.iso(rs, "updated_at"),
                Timestamps.iso(rs, "last_run_at"), Timestamps.iso(rs, "next_run_at"));
    }

    /**
     * Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un
     * run.
     */
    @Override
    public boolean touchLastRunAt(long jobId, String lastRunAt) {
        try {
            return jdbc.update("UPDATE cron_jobs SET last_run_at=?::timestamptz WHERE id=?", ps -> {
                ps.setString(1, lastRunAt);
                ps.setLong(2, jobId);
            }) > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /**
     * Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll.
     */
    @Override
    public boolean updateNextRunAt(long jobId, String nextRunAt) {
        try {
            return jdbc.update("UPDATE cron_jobs SET next_run_at=?::timestamptz WHERE id=?", ps -> {
                ps.setString(1, nextRunAt);
                ps.setLong(2, jobId);
            }) > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando next_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    @Override
    public long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO cron_executions (job_id, started_at, status, skipped_reason)
                        VALUES (?,?::timestamptz,?,?)
                        """, new String[]{"id"});
                ps.setLong(1, jobId);
                ps.setString(2, startedAt);
                ps.setString(3, status);
                ps.setString(4, skippedReason);
                return ps;
            }, keys);
            Number key = keys.getKey();
            return key != null ? key.longValue() : -1;
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron execution (job {}): {}", jobId, e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        try {
            return jdbc.update("""
                    UPDATE cron_executions
                    SET finished_at=?::timestamptz, status=?, skipped_reason=?, log_output=?, duration_ms=?
                    WHERE id=?
                    """, ps -> {
                ps.setString(1, finishedAt);
                ps.setString(2, status);
                ps.setString(3, skippedReason);
                ps.setString(4, logOutput);
                if (durationMs != null) ps.setInt(5, durationMs); else ps.setNull(5, Types.INTEGER);
                ps.setLong(6, execId);
            }) > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron execution {}: {}", execId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<CronExecution> listExecutions(long jobId, int limit) {
        List<CronExecution> result = new ArrayList<>();
        try {
            jdbc.query("SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                       "FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?",
                    ps -> {
                        ps.setLong(1, jobId);
                        ps.setInt(2, limit);
                    },
                    rs -> {
                        result.add(cronExecutionDesdeFila(rs));
                    });
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron executions (job {}): {}", jobId, e.getMessage());
        }
        return result;
    }

    @Override
    public Optional<CronExecution> getExecution(long execId) {
        try {
            return jdbc.query("SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                              "FROM cron_executions WHERE id=?",
                    ps -> ps.setLong(1, execId),
                    rs -> rs.next() ? Optional.of(cronExecutionDesdeFila(rs)) : Optional.<CronExecution>empty());
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
                Timestamps.iso(rs, "started_at"), Timestamps.iso(rs, "finished_at"),
                rs.getString("status"), rs.getString("skipped_reason"),
                rs.getString("log_output"), duration);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    @Override
    public void pruneCronExecutions(long jobId, int keep) {
        try {
            jdbc.update("""
                    DELETE FROM cron_executions WHERE job_id=? AND id NOT IN (
                        SELECT id FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?
                    )
                    """, ps -> {
                ps.setLong(1, jobId);
                ps.setLong(2, jobId);
                ps.setInt(3, keep);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error pruning cron executions (job {}): {}", jobId, e.getMessage());
        }
    }
}
