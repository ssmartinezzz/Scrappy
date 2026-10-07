package ar.scraper.db;

import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.CorridaInterrumpida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * {@code started_at} is the reader-isolation bound, the site rows are the authoritative site set
 * for a resume, and a row left {@code RUNNING} with no {@code finished_at} is how a crash is
 * detected on the next boot.
 */
@Repository
class ScrapeRunRepository implements ScrapeRunPort {

    private static final Logger LOG = LoggerFactory.getLogger(ScrapeRunRepository.class);

    /**
     * The one spelling of the site-key normalization, byte-identical to
     * {@code R__sp_upsert_run.sql:97} and to {@code productos.sitio_key}'s generation expression.
     */
    private static final String SITIO_KEY_SQL =
            "lower(regexp_replace(?, '[^a-zA-Z0-9]', '', 'g'))";

    private final JdbcTemplate jdbc;

    ScrapeRunRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Opens a run and enrolls its sites as {@code PENDING}, in one transaction: a run whose site
     * rows failed to land would report an empty site set to a later resume, which reads as "nothing
     * left to do".
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long crear(UUID scrapeUuid, Instant startedAt, UUID triggeredBy, Long cronJobId, Collection<String> sitios) {
        return Sql.traducir(() -> crearSql(scrapeUuid, startedAt, triggeredBy, cronJobId, sitios));
    }

    private long crearSql(UUID scrapeUuid, Instant startedAt, UUID triggeredBy, Long cronJobId, Collection<String> sitios) throws SQLException {
        Instant arranque = truncarAlSegundo(startedAt);

        long runId = insertarRun(scrapeUuid, arranque, triggeredBy, cronJobId);
        for (String sitio : sitios) {
            if (StringUtils.isBlank(sitio)) continue;
            asegurarSitio(sitio);
            enrolarSitio(runId, sitio);
        }
        return runId;
    }

    private long insertarRun(UUID scrapeUuid, Instant startedAt,
                             UUID triggeredBy, Long cronJobId) throws SQLException {
        String sql = """
            INSERT INTO scrape_run (scrape_uuid, started_at, triggered_by, cron_job_id, status)
            VALUES (?, ?, ?, ?, 'RUNNING')
            RETURNING id
            """;
        Long id = jdbc.query(sql, ps -> {
            ps.setObject(1, scrapeUuid);
            ps.setObject(2, enUtc(startedAt));
            if (triggeredBy != null) ps.setObject(3, triggeredBy); else ps.setNull(3, Types.OTHER);
            if (cronJobId != null) ps.setLong(4, cronJobId); else ps.setNull(4, Types.BIGINT);
        }, rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) throw new SQLException("scrape_run INSERT returned no id");
        return id;
    }

    private void asegurarSitio(String sitio) {
        String sql = """
            INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
            SELECT ?, %s, 'tiendanube', false, NULL, 'historico'
            ON CONFLICT DO NOTHING
            """.formatted(SITIO_KEY_SQL);
        jdbc.update(sql, sitio, sitio);
    }

    private void enrolarSitio(long runId, String sitio) {
        String sql = """
            INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status)
            VALUES (?, %s, 'PENDING')
            ON CONFLICT (scrape_run_id, sitio_key) DO NOTHING
            """.formatted(SITIO_KEY_SQL);
        jdbc.update(sql, runId, sitio);
    }

    @Override
    public void marcarSitioEnCurso(long runId, String sitio, Instant cuando) {
        Sql.traducir(() -> marcarSitioEnCursoSql(runId, sitio, cuando));
    }

    private void marcarSitioEnCursoSql(long runId, String sitio, Instant cuando) {
        String sql = """
            UPDATE scrape_run_site SET status = 'RUNNING', started_at = ?
            WHERE scrape_run_id = ? AND sitio_key = %s
            """.formatted(SITIO_KEY_SQL);
        jdbc.update(sql, ps -> {
            ps.setObject(1, enUtc(cuando));
            ps.setLong(2, runId);
            ps.setString(3, sitio);
        });
    }

    @Override
    public void marcarSitioTerminado(long runId, String sitio, String status, int productosCount, String error, Instant cuando) {
        Sql.traducir(() -> marcarSitioTerminadoSql(runId, sitio, status, productosCount, error, cuando));
    }

    private void marcarSitioTerminadoSql(long runId, String sitio, String status, int productosCount, String error, Instant cuando) {
        String sql = """
            UPDATE scrape_run_site
               SET status = ?, productos_count = ?, error = ?, finished_at = ?
             WHERE scrape_run_id = ? AND sitio_key = %s
            """.formatted(SITIO_KEY_SQL);
        jdbc.update(sql, ps -> {
            ps.setString(1, status);
            ps.setInt(2, productosCount);
            ps.setString(3, error);
            ps.setObject(4, enUtc(cuando));
            ps.setLong(5, runId);
            ps.setString(6, sitio);
        });
    }

    /**
     * The terminal status and {@code finished_at} go in the same statement because
     * {@code ck_scrape_run_running_iff_unfinished} rejects any row where they disagree — they
     * cannot be written apart even by accident.
     */
    @Override
    public void finalizar(long runId, String status, int productosCount, Instant finishedAt) {
        Sql.traducir(() -> finalizarSql(runId, status, productosCount, finishedAt));
    }

    private void finalizarSql(long runId, String status, int productosCount, Instant finishedAt) {
        String sql = """
            UPDATE scrape_run SET status = ?, productos_count = ?, finished_at = ?
             WHERE id = ?
            """;
        jdbc.update(sql, ps -> {
            ps.setString(1, status);
            ps.setInt(2, productosCount);
            ps.setObject(3, enUtc(finishedAt));
            ps.setLong(4, runId);
        });
    }

    /**
     * Marking is also what keeps the signal single-valued: without it a second restart would find
     * two runs still claiming to be live, and "the interrupted run" would stop naming one thing.
     */
    @Override
    public List<Long> marcarInterrumpidosAlArrancar(Instant cuando) {
        return Sql.traducir(() -> marcarInterrumpidosAlArrancarSql(cuando));
    }

    private List<Long> marcarInterrumpidosAlArrancarSql(Instant cuando) {
        String sql = """
            UPDATE scrape_run SET status = 'INTERRUPTED', finished_at = ?
             WHERE status = 'RUNNING' AND finished_at IS NULL
            RETURNING id
            """;
        List<Long> ids = new ArrayList<>();
        jdbc.query(sql, ps -> ps.setObject(1, enUtc(cuando)), rs -> {
            ids.add(rs.getLong(1));
        });
        if (!ids.isEmpty()) {
            LOG.warn("[DB] {} corrida(s) quedaron abiertas por un proceso anterior: {}",
                    ids.size(), ids);
        }
        return ids;
    }

    /**
     * Most recent rather than "all of them": two interrupted runs mean two crashes, and resuming
     * the older one would re-scrape against a bound a newer run already moved past.
     */
    @Override
    public Optional<CorridaInterrumpida> ultimaInterrumpida() {
        return Sql.traducir(() -> ultimaInterrumpidaSql());
    }

    private Optional<CorridaInterrumpida> ultimaInterrumpidaSql() {
        String sql = """
            SELECT id, scrape_uuid, started_at FROM scrape_run
             WHERE status = 'INTERRUPTED'
             ORDER BY started_at DESC, id DESC
             LIMIT 1
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return Optional.<CorridaInterrumpida>empty();
            long runId = rs.getLong(1);
            UUID uuid = (UUID) rs.getObject(2);
            Instant startedAt = rs.getObject(3, OffsetDateTime.class).toInstant();
            return Optional.of(new CorridaInterrumpida(
                    runId, uuid, startedAt,
                    sitiosEn(runId, "DONE", "ERROR"),
                    sitiosEn(runId, "PENDING", "RUNNING"),
                    sitiosEn(runId, "SKIPPED")));
        });
    }

    private List<String> sitiosEn(long runId, String... estados) {
        String sql = """
            SELECT sitio_key FROM scrape_run_site
             WHERE scrape_run_id = ? AND status = ANY (?)
             ORDER BY sitio_key
            """;
        List<String> out = new ArrayList<>();
        jdbc.query(sql, ps -> {
            ps.setLong(1, runId);
            ps.setArray(2, ps.getConnection().createArrayOf("text", estados));
        }, rs -> {
            out.add(rs.getString(1));
        });
        return out;
    }

    /**
     * A new run row would make both name only the resumed half — the sweep would then see the first
     * half's products as absent and deactivate them, which is strictly worse than the interruption
     * it was meant to repair.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reabrir(long runId) {
        Sql.traducir(() -> reabrirSql(runId));
    }

    private void reabrirSql(long runId) {
        jdbc.update("UPDATE scrape_run SET status = 'RUNNING', finished_at = NULL WHERE id = ?", runId);
        jdbc.update("""
                UPDATE scrape_run_site SET status = 'PENDING', started_at = NULL
                 WHERE scrape_run_id = ? AND status = 'RUNNING'
                """, runId);
    }

    /**
     * {@code finished_at} is filled only when missing. A run marked INTERRUPTED at boot already has
     * one — the moment the interruption was noticed — and overwriting it would move the end of a
     * run that ended days ago.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> descartarInterrumpidas(Instant cuando) {
        return Sql.traducir(() -> descartarInterrumpidasSql(cuando));
    }

    private List<Long> descartarInterrumpidasSql(Instant cuando) {
        List<Long> ids = new ArrayList<>();
        jdbc.query("""
                UPDATE scrape_run
                   SET status = 'CANCELLED',
                       finished_at = COALESCE(finished_at, ?)
                 WHERE status = 'INTERRUPTED'
                RETURNING id
                """, ps -> ps.setObject(1, enUtc(cuando)), rs -> {
            ids.add(rs.getLong(1));
        });
        for (long runId : ids) {
            jdbc.update("""
                    UPDATE scrape_run_site SET status = 'SKIPPED'
                     WHERE scrape_run_id = ? AND status IN ('PENDING', 'RUNNING')
                    """, runId);
        }
        if (!ids.isEmpty()) LOG.warn("[RUN] {} corrida(s) interrumpida(s) descartadas: {}", ids.size(), ids);
        return ids;
    }

    /**
     * The comparison runs in SQL, normalizing the current names with the same expression that
     * produced the stored keys.
     */
    @Override
    public List<String> marcarAusentesDelRegistro(long runId, java.util.Collection<String> nombresActuales) {
        return Sql.traducir(() -> marcarAusentesDelRegistroSql(runId, nombresActuales));
    }

    private List<String> marcarAusentesDelRegistroSql(long runId, java.util.Collection<String> nombresActuales) {
        String sql = """
            UPDATE scrape_run_site SET status = 'SKIPPED'
             WHERE scrape_run_id = ?
               AND status IN ('PENDING', 'RUNNING')
               AND sitio_key <> ALL (
                     SELECT lower(regexp_replace(n, '[^a-zA-Z0-9]', '', 'g'))
                       FROM unnest(?::text[]) AS n)
            RETURNING sitio_key
            """;
        List<String> out = new ArrayList<>();
        jdbc.query(sql, ps -> {
            ps.setLong(1, runId);
            ps.setArray(2, ps.getConnection().createArrayOf("text", nombresActuales.toArray()));
        }, rs -> {
            out.add(rs.getString(1));
        });
        if (!out.isEmpty()) {
            LOG.warn("[RUN] {} sitio(s) de la corrida interrumpida ya no están "
                     + "en el registro, se marcan SKIPPED: {}", out.size(), out);
        }
        return out;
    }

    /**
     * {@code COMPLETED}, not "any run": on a fresh install the first run is itself a run, so "any"
     * would apply the bound while nothing can satisfy {@code touched_at < started_at} and the
     * reader would get an empty screen.
     */
    @Override
    public boolean existeCorridaCompletada() {
        return Sql.traducir(() -> existeCorridaCompletadaSql());
    }

    private boolean existeCorridaCompletadaSql() {
        return jdbc.query("SELECT EXISTS (SELECT 1 FROM scrape_run WHERE status = 'COMPLETED')",
                rs -> rs.next() && rs.getBoolean(1));
    }

    @Override
    public Optional<Instant> startedAtDe(long runId) {
        return Sql.traducir(() -> startedAtDeSql(runId));
    }

    private Optional<Instant> startedAtDeSql(long runId) {
        return jdbc.query("SELECT started_at FROM scrape_run WHERE id = ?",
                ps -> ps.setLong(1, runId), rs -> {
            if (!rs.next()) return Optional.<Instant>empty();
            OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
            return Optional.ofNullable(odt).map(OffsetDateTime::toInstant);
        });
    }

    /**
     * An untruncated {@code started_at} would therefore make {@code touched_at >= started_at}
     * exclude every row touched during the run's own first second — and the soft-delete union built
     * on that predicate would read those products as absent and deactivate them.
     */
    private static Instant truncarAlSegundo(Instant instante) {
        return instante.truncatedTo(ChronoUnit.SECONDS);
    }

    @Override
    public Map<String, Long> duracionesHistoricasMs() {
        return Sql.traducir(this::duracionesHistoricasMsSql);
    }

    private Map<String, Long> duracionesHistoricasMsSql() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sitio_key,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS mediana_ms
                  FROM (SELECT sitio_key,
                               EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000 AS ms,
                               ROW_NUMBER() OVER (PARTITION BY sitio_key
                                                  ORDER BY finished_at DESC) AS n
                          FROM scrape_run_site
                         WHERE status = 'DONE'
                           AND started_at IS NOT NULL AND finished_at IS NOT NULL) ultimas
                 WHERE n <= 3
                 GROUP BY sitio_key
                """, rs -> {
            out.put(rs.getString(1), Math.round(rs.getDouble(2)));
        });
        return out;
    }

    private static OffsetDateTime enUtc(Instant instante) {
        return instante.atOffset(ZoneOffset.UTC);
    }
}
