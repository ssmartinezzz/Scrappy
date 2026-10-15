package ar.scraper.db;

import ar.scraper.notificaciones.BajaReclamada;
import ar.scraper.notificaciones.FalloDeEnvio;
import ar.scraper.notificaciones.NotificacionOutboxPort;
import ar.scraper.notificaciones.Reclamo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence for {@code notificacion_precio}; the table is filled by {@code sp_upsert_run}. */
@Repository
public class NotificacionOutboxRepository implements NotificacionOutboxPort {

    private static final int ERROR_MAX_CHARS = 500;
    private static final int TROZO_DE_PURGA = 5000;
    static final String SQL_CANDIDATAS_DE_PURGA = """
            SELECT id FROM notificacion_precio
             WHERE estado IN ('ENVIADA', 'FALLIDA')
               AND COALESCE(enviada_at, proximo_intento_at) < now() - make_interval(secs => ?)
             ORDER BY COALESCE(enviada_at, proximo_intento_at)
             LIMIT ?
            """;
    private static final long CANDADO_DEL_TOPE = 0x4E4F544946L;

    private final JdbcTemplate jdbc;

    public NotificacionOutboxRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * One short transaction: the claim only flips the lease and the attempt counter, so the locks
     * are gone before any mail is sent.
     *
     * <p>Whole recipients are claimed, not rows: the lock is taken on the {@code usuario} row
     * (FOR NO KEY UPDATE, which does not conflict with the FOR KEY SHARE an enqueue takes through
     * the FK) and then every due row of that user is leased in the same statement. Locking only
     * the outbox rows with SKIP LOCKED would let two relays each take half of one user's drops and
     * send two mails.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BajaReclamada> reclamar(int maxDestinatarios, int maxIntentos, Duration lease, int topePorMinuto) {
        retirarLasQueNoTienenDestino();
        retirarLasAgotadasConLeaseVencido(maxIntentos);
        int destinatarios = topePorMinuto > 0 ? cupoBajoTope(maxDestinatarios, topePorMinuto) : maxDestinatarios;
        if (destinatarios <= 0) {
            return List.of();
        }
        return jdbc.query("""
                WITH destinatarios AS (
                    SELECT u.id
                      FROM (SELECT usuario_id, min(proximo_intento_at) AS desde
                              FROM notificacion_precio
                             WHERE estado = 'PENDIENTE' AND proximo_intento_at <= now() AND intentos < ?
                             GROUP BY usuario_id) d
                      JOIN usuario u ON u.id = d.usuario_id
                     ORDER BY d.desde, u.id
                     LIMIT ?
                       FOR NO KEY UPDATE OF u SKIP LOCKED
                )
                UPDATE notificacion_precio n
                   SET intentos = n.intentos + 1,
                       proximo_intento_at = now() + make_interval(secs => ?),
                       lease_hasta = now() + make_interval(secs => ?)
                  FROM destinatarios d, usuario u, productos p
                 WHERE n.usuario_id = d.id AND u.id = d.id AND p.url = n.url
                   AND n.estado = 'PENDIENTE' AND n.proximo_intento_at <= now() AND n.intentos < ?
                RETURNING n.id, n.usuario_id, u.email, n.url, p.nombre, p.sitio,
                          n.precio_anterior, n.precio_nuevo, n.intentos
                """, (rs, i) -> new BajaReclamada(
                rs.getLong("id"), rs.getObject("usuario_id", UUID.class), rs.getString("email"),
                rs.getString("url"), rs.getString("nombre"), rs.getString("sitio"),
                rs.getDouble("precio_anterior"), rs.getDouble("precio_nuevo"), rs.getInt("intentos")),
                maxIntentos, destinatarios, lease.toMillis() / 1000.0, lease.toMillis() / 1000.0, maxIntentos);
    }

    /**
     * Serialises capped claimers for the length of this transaction, so each one sees the leases
     * the previous one committed. Uncapped claimers never come here and keep full parallelism.
     */
    private int cupoBajoTope(int maxDestinatarios, int topePorMinuto) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, CANDADO_DEL_TOPE);
        Integer usados = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT usuario_id FROM notificacion_precio
                     WHERE estado = 'ENVIADA' AND enviada_at > now() - interval '60 seconds'
                    UNION
                    SELECT usuario_id FROM notificacion_precio
                     WHERE estado = 'PENDIENTE' AND lease_hasta > now()
                ) q
                """, Integer.class);
        return Math.min(maxDestinatarios, topePorMinuto - usados);
    }

    @Override
    public void marcarEnviadas(Collection<Reclamo> reclamos) {
        if (reclamos.isEmpty()) return;
        Long[] ids = reclamos.stream().map(Reclamo::id).toArray(Long[]::new);
        Integer[] intentos = reclamos.stream().map(Reclamo::intentos).toArray(Integer[]::new);
        jdbc.update("""
                UPDATE notificacion_precio n
                   SET estado = 'ENVIADA', enviada_at = now(), ultimo_error = NULL, lease_hasta = NULL
                  FROM unnest(?::bigint[], ?::int[]) AS r(id, intentos)
                 WHERE n.id = r.id AND n.intentos = r.intentos AND n.estado = 'PENDIENTE'
                """, ids, intentos);
    }

    @Override
    public void registrarFallos(Collection<FalloDeEnvio> fallos, int maxIntentos, Duration backoffBase) {
        if (fallos.isEmpty()) return;
        Long[] ids = fallos.stream().map(f -> f.reclamo().id()).toArray(Long[]::new);
        Integer[] reclamados = fallos.stream().map(f -> f.reclamo().intentos()).toArray(Integer[]::new);
        String[] errores = fallos.stream().map(FalloDeEnvio::error).toArray(String[]::new);
        Boolean[] permanentes = fallos.stream().map(FalloDeEnvio::permanente).toArray(Boolean[]::new);
        jdbc.update("""
                UPDATE notificacion_precio n
                   SET ultimo_error = left(f.error, %d),
                       lease_hasta = NULL,
                       estado = CASE WHEN f.permanente OR n.intentos >= ? THEN 'FALLIDA' ELSE 'PENDIENTE' END,
                       proximo_intento_at = now()
                           + make_interval(secs => ? * power(2, greatest(n.intentos - 1, 0)))
                  FROM unnest(?::bigint[], ?::int[], ?::text[], ?::boolean[]) AS f(id, intentos, error, permanente)
                 WHERE n.id = f.id AND n.intentos = f.intentos AND n.estado = 'PENDIENTE'
                """.formatted(ERROR_MAX_CHARS),
                maxIntentos, backoffBase.toMillis() / 1000.0, ids, reclamados, errores, permanentes);
    }

    @Override
    public int purgarTerminadas(Duration retencion) {
        return purgarTerminadas(retencion, TROZO_DE_PURGA);
    }

    /**
     * Each chunk is its own statement and so its own transaction: a purge of millions of rows
     * never holds one long lock nor produces one giant WAL record. Oldest first, so an
     * interrupted purge leaves the newest rows for the next pass.
     */
    int purgarTerminadas(Duration retencion, int trozo) {
        double segundos = retencion.toMillis() / 1000.0;
        int total = 0;
        int borradas;
        do {
            borradas = jdbc.update("DELETE FROM notificacion_precio WHERE id IN (" + SQL_CANDIDATAS_DE_PURGA + ")",
                    segundos, trozo);
            total += borradas;
        } while (borradas == trozo);
        return total;
    }

    /** A usuario who deactivated, opted out or lost the email after the row was queued. */
    private void retirarLasQueNoTienenDestino() {
        jdbc.update("""
                UPDATE notificacion_precio n
                   SET estado = 'FALLIDA', ultimo_error = 'sin destinatario elegible', lease_hasta = NULL
                  FROM usuario u
                 WHERE u.id = n.usuario_id AND n.estado = 'PENDIENTE'
                   AND NOT (u.activo AND u.email IS NOT NULL AND u.notificar_bajas_precio)
                """);
    }

    /** A crash on the last attempt leaves a leased row nobody will claim; it must not stay PENDIENTE. */
    private void retirarLasAgotadasConLeaseVencido(int maxIntentos) {
        jdbc.update("""
                UPDATE notificacion_precio
                   SET estado = 'FALLIDA', ultimo_error = 'intentos agotados', lease_hasta = NULL
                 WHERE estado = 'PENDIENTE' AND intentos >= ? AND proximo_intento_at <= now()
                """, maxIntentos);
    }
}
