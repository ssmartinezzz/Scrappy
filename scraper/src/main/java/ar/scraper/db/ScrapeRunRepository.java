package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code scrape_run} and {@code scrape_run_site} (V29).
 *
 * <p>A run is an addressable entity that outlives the process that started it.
 * Three later slices read what this class writes: {@code started_at} is the
 * reader-isolation bound, the site rows are the authoritative site set for a
 * resume, and a row left {@code RUNNING} with no {@code finished_at} is how a
 * crash is detected on the next boot.</p>
 *
 * <h2>Two things here are deliberate and easy to "clean up" into bugs</h2>
 *
 * <p><b>The site key is normalized in SQL, never in Java.</b> There are already
 * two copies of that normalization — {@code SiteClassification.sitioKey()} and
 * the expression in {@code R__sp_upsert_run.sql:97} — and they are not
 * equivalent: Java lowercases and then filters against {@code [a-z0-9]}, the
 * SQL filters against {@code [a-zA-Z0-9]} and then lowercases. Under a Turkish
 * locale {@code "INPRO".toLowerCase()} is {@code "ınpro"} (dotless i), which
 * fails the Java filter, so Java yields {@code npro} where SQL yields
 * {@code inpro}. Using the SQL expression here does not merely avoid a third
 * copy: it makes it structurally impossible for
 * {@code scrape_run_site.sitio_key} to disagree with
 * {@code productos.sitio_key}, whatever locale the JVM runs under.</p>
 *
 * <p><b>The site rows need a get-or-create.</b> {@code V23}'s FK on
 * {@code productos} only survives because {@code sp_upsert_run} seeds
 * {@code sitio} before inserting the product that references it. These rows go
 * in at run <i>start</i>, before any scraping, so that seeding has not run yet
 * — a site added through {@code /api/sitios} and never scraped would make the
 * run fail to start at all.</p>
 */
class ScrapeRunRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ScrapeRunRepository.class);

    /**
     * The one spelling of the site-key normalization, byte-identical to
     * {@code R__sp_upsert_run.sql:97} and to {@code productos.sitio_key}'s
     * generation expression. Never re-implement this in Java — see the class
     * javadoc for what the two existing copies disagree about.
     */
    private static final String SITIO_KEY_SQL =
            "lower(regexp_replace(?, '[^a-zA-Z0-9]', '', 'g'))";

    private final DataSource dataSource;

    ScrapeRunRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Opens a run and enrolls its sites as {@code PENDING}, in one transaction:
     * a run whose site rows failed to land would report an empty site set to a
     * later resume, which reads as "nothing left to do".
     */
    long crear(UUID scrapeUuid, Instant startedAt, UUID triggeredBy, Long cronJobId,
               Collection<String> sitios) throws SQLException {
        Instant arranque = truncarAlSegundo(startedAt);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long runId = insertarRun(c, scrapeUuid, arranque, triggeredBy, cronJobId);
                for (String sitio : sitios) {
                    if (sitio == null || sitio.isBlank()) continue;
                    asegurarSitio(c, sitio);
                    enrolarSitio(c, runId, sitio);
                }
                c.commit();
                return runId;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private long insertarRun(Connection c, UUID scrapeUuid, Instant startedAt,
                             UUID triggeredBy, Long cronJobId) throws SQLException {
        String sql = """
            INSERT INTO scrape_run (scrape_uuid, started_at, triggered_by, cron_job_id, status)
            VALUES (?, ?, ?, ?, 'RUNNING')
            RETURNING id
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, scrapeUuid);
            ps.setObject(2, enUtc(startedAt));
            if (triggeredBy != null) ps.setObject(3, triggeredBy); else ps.setNull(3, Types.OTHER);
            if (cronJobId != null) ps.setLong(4, cronJobId); else ps.setNull(4, Types.BIGINT);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("scrape_run INSERT returned no id");
                return rs.getLong(1);
            }
        }
    }

    /** Mirrors {@code sp_upsert_run}'s get-or-create, including its untargeted conflict clause. */
    private void asegurarSitio(Connection c, String sitio) throws SQLException {
        String sql = """
            INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
            SELECT ?, %s, 'tiendanube', false, NULL, 'historico'
            ON CONFLICT DO NOTHING
            """.formatted(SITIO_KEY_SQL);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sitio);
            ps.setString(2, sitio);
            ps.executeUpdate();
        }
    }

    private void enrolarSitio(Connection c, long runId, String sitio) throws SQLException {
        String sql = """
            INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status)
            VALUES (?, %s, 'PENDING')
            ON CONFLICT (scrape_run_id, sitio_key) DO NOTHING
            """.formatted(SITIO_KEY_SQL);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setString(2, sitio);
            ps.executeUpdate();
        }
    }

    void marcarSitioEnCurso(long runId, String sitio, Instant cuando) throws SQLException {
        String sql = """
            UPDATE scrape_run_site SET status = 'RUNNING', started_at = ?
            WHERE scrape_run_id = ? AND sitio_key = %s
            """.formatted(SITIO_KEY_SQL);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, enUtc(cuando));
            ps.setLong(2, runId);
            ps.setString(3, sitio);
            ps.executeUpdate();
        }
    }

    void marcarSitioTerminado(long runId, String sitio, String status, int productosCount,
                              String error, Instant cuando) throws SQLException {
        String sql = """
            UPDATE scrape_run_site
               SET status = ?, productos_count = ?, error = ?, finished_at = ?
             WHERE scrape_run_id = ? AND sitio_key = %s
            """.formatted(SITIO_KEY_SQL);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, productosCount);
            ps.setString(3, error);
            ps.setObject(4, enUtc(cuando));
            ps.setLong(5, runId);
            ps.setString(6, sitio);
            ps.executeUpdate();
        }
    }

    /**
     * Closes the run. The terminal status and {@code finished_at} go in the same
     * statement because {@code ck_scrape_run_running_iff_unfinished} rejects any
     * row where they disagree — they cannot be written apart even by accident.
     */
    void finalizar(long runId, String status, int productosCount, Instant finishedAt)
            throws SQLException {
        String sql = """
            UPDATE scrape_run SET status = ?, productos_count = ?, finished_at = ?
             WHERE id = ?
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, productosCount);
            ps.setObject(3, enUtc(finishedAt));
            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }

    /**
     * Marks every run the last process left open as {@code INTERRUPTED} and
     * returns their ids. Called once at boot.
     *
     * <p>This <b>only</b> marks — it never starts a scrape. Marking is also what
     * keeps the signal single-valued: without it a second restart would find two
     * runs still claiming to be live, and "the interrupted run" would stop
     * naming one thing.</p>
     */
    List<Long> marcarInterrumpidosAlArrancar(Instant cuando) throws SQLException {
        String sql = """
            UPDATE scrape_run SET status = 'INTERRUPTED', finished_at = ?
             WHERE status = 'RUNNING' AND finished_at IS NULL
            RETURNING id
            """;
        List<Long> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, enUtc(cuando));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        if (!ids.isEmpty()) {
            LOG.warn("[DB] {} corrida(s) quedaron abiertas por un proceso anterior: {}",
                    ids.size(), ids);
        }
        return ids;
    }

    /**
     * The most recent run marked INTERRUPTED, if any.
     *
     * <p>Most recent rather than "all of them": two interrupted runs mean two
     * crashes, and resuming the older one would re-scrape against a bound a
     * newer run already moved past.</p>
     */
    Optional<CorridaInterrumpida> ultimaInterrumpida() throws SQLException {
        String sql = """
            SELECT id, scrape_uuid, started_at FROM scrape_run
             WHERE status = 'INTERRUPTED'
             ORDER BY started_at DESC, id DESC
             LIMIT 1
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            long runId = rs.getLong(1);
            UUID uuid = (UUID) rs.getObject(2);
            Instant startedAt = rs.getObject(3, OffsetDateTime.class).toInstant();
            return Optional.of(new CorridaInterrumpida(
                    runId, uuid, startedAt,
                    sitiosEn(runId, "DONE", "ERROR"),
                    sitiosEn(runId, "PENDING", "RUNNING"),
                    sitiosEn(runId, "SKIPPED")));
        }
    }

    private List<String> sitiosEn(long runId, String... estados) throws SQLException {
        String sql = """
            SELECT sitio_key FROM scrape_run_site
             WHERE scrape_run_id = ? AND status = ANY (?)
             ORDER BY sitio_key
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setArray(2, c.createArrayOf("text", estados));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /**
     * Puts an interrupted run back to RUNNING, <b>in place</b>.
     *
     * <p>{@code started_at} is untouched, and that is the entire point: it is the
     * reader-isolation bound and the scope of the final soft-delete sweep. A new
     * run row would make both name only the resumed half — the sweep would then
     * see the first half's products as absent and deactivate them, which is
     * strictly worse than the interruption it was meant to repair.</p>
     *
     * <p>Sites caught mid-scrape go back to PENDING: their partial result died
     * with the process, so they are owed exactly as much as one that never
     * started.</p>
     */
    void reabrir(long runId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE scrape_run SET status = 'RUNNING', finished_at = NULL WHERE id = ?")) {
                    ps.setLong(1, runId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE scrape_run_site SET status = 'PENDING', started_at = NULL
                         WHERE scrape_run_id = ? AND status = 'RUNNING'
                        """)) {
                    ps.setLong(1, runId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Marks sites gone from the registry, so they get named instead of silently dropped. */
    void marcarSitiosSalteados(long runId, java.util.Collection<String> sitioKeys)
            throws SQLException {
        if (sitioKeys.isEmpty()) return;
        String sql = """
            UPDATE scrape_run_site SET status = 'SKIPPED'
             WHERE scrape_run_id = ? AND sitio_key = ANY (?)
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setArray(2, c.createArrayOf("text", sitioKeys.toArray()));
            ps.executeUpdate();
        }
    }

    /**
     * Marks as SKIPPED any still-pending site that is no longer in the registry,
     * and returns which ones.
     *
     * <p>The comparison runs <b>in SQL</b>, normalizing the current names with the
     * same expression that produced the stored keys. Doing it in Java would
     * reintroduce the divergence this class avoids everywhere else — and here it
     * would not merely disagree, it would decide: a name that normalizes
     * differently looks absent from the registry, gets marked SKIPPED, and is
     * silently dropped from a resume that owed it.</p>
     */
    List<String> marcarAusentesDelRegistro(long runId, java.util.Collection<String> nombresActuales)
            throws SQLException {
        String sql = """
            UPDATE scrape_run_site SET status = 'SKIPPED'
             WHERE scrape_run_id = ?
               AND status IN ('PENDING', 'RUNNING')
               AND sitio_key <> ALL (
                     SELECT lower(regexp_replace(n, '[^a-zA-Z0-9]', '', 'g'))
                       FROM unnest(?::text[]) AS n)
            RETURNING sitio_key
            """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setArray(2, c.createArrayOf("text", nombresActuales.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                if (!out.isEmpty()) {
                    LOG.warn("[RUN] {} sitio(s) de la corrida interrumpida ya no están "
                             + "en el registro, se marcan SKIPPED: {}", out.size(), out);
                }
                return out;
            }
        }
    }

    Optional<Instant> startedAtDe(long runId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT started_at FROM scrape_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
                return Optional.ofNullable(odt).map(OffsetDateTime::toInstant);
            }
        }
    }

    /**
     * The run clock, floored to whole seconds to match what {@code touched_at}
     * can actually hold.
     *
     * <p>{@code productos.touched_at} is a {@code timestamptz} — microseconds —
     * but every value written into it comes from
     * {@code LocalDateTime.now().format("yyyy-MM-dd HH:mm:ss")}
     * ({@code ProductRepository:44}, used at {@code :71} and {@code :213}), so
     * in practice the column only ever holds {@code .000000}. An untruncated
     * {@code started_at} would therefore make {@code touched_at >= started_at}
     * exclude every row touched during the run's own first second — and the
     * soft-delete union built on that predicate would read those products as
     * absent and deactivate them.</p>
     *
     * <p>This is a method rather than a note on {@code crear} on purpose. The
     * design's rule — "the same Java clock, never {@code DEFAULT now()}" — can
     * be obeyed to the letter with {@code Timestamp.from(Instant.now())} and
     * still ship the bug, so the guarantee lives in code that callers cannot
     * route around.</p>
     *
     * <p><b>What the two predicates do with a first-second row, since it is easy
     * to get backwards.</b> The soft-delete union is {@code touched_at >=
     * started_at} and the reader bound is {@code touched_at < started_at}. A row
     * touched in the run's own first second has {@code touched_at ==
     * started_at}, so the union <b>includes</b> it and the reader
     * <b>excludes</b> it. Both are right, and they agree: that row belongs to the
     * in-flight run, which is exactly what the union has to sweep and exactly
     * what the reader has to hide. Hiding it is the isolation working, not a
     * gap — do not "fix" it.</p>
     *
     * <p><b>The one genuine asymmetry, accepted deliberately:</b> truncation
     * WIDENS both predicates by up to a second, so a row written in the same
     * second but <i>before</i> the run opened is also treated as the run's. For
     * the union that direction is the safe one — it protects a row from the
     * sweep, never sweeps an extra one. For the reader it means that row stays
     * hidden until the run ends: a sub-second-old write, invisible for the
     * duration. Nobody loses data, and the alternative was a
     * {@code scrape_run_id} column on {@code productos}, which the design
     * rejected for touching the hottest table in the schema.</p>
     */
    private static Instant truncarAlSegundo(Instant instante) {
        return instante.truncatedTo(ChronoUnit.SECONDS);
    }

    private static OffsetDateTime enUtc(Instant instante) {
        return instante.atOffset(ZoneOffset.UTC);
    }
}
