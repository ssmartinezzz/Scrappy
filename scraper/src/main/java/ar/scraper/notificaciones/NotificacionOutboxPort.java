package ar.scraper.notificaciones;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * The outbox is at-least-once: a claimed row that is never marked comes back once its lease
 * expires, so a crash between sending and marking can send twice but never zero times.
 */
public interface NotificacionOutboxPort {

    /**
     * Claims every due row of up to {@code maxDestinatarios} recipients for {@code lease}, counting
     * one attempt on each. Concurrent claimers never receive the same row, nor split one
     * recipient's rows between them.
     */
    default List<BajaReclamada> reclamar(int maxDestinatarios, int maxIntentos, Duration lease) {
        return reclamar(maxDestinatarios, maxIntentos, lease, 0);
    }

    /**
     * As above, but never lets the cluster exceed {@code topePorMinuto} distinct recipients mailed in
     * a rolling minute, counting those already sent and those leased to a relay right now, whichever
     * backend holds them. Zero means no cap and no coordination between claimers.
     */
    List<BajaReclamada> reclamar(int maxDestinatarios, int maxIntentos, Duration lease, int topePorMinuto);

    void marcarEnviadas(Collection<Long> ids);

    /**
     * Reschedules each row with exponential backoff from {@code backoffBase}, or gives up at
     * {@code maxIntentos}; one statement however many rows and however many distinct errors.
     */
    void registrarFallos(Collection<FalloDeEnvio> fallos, int maxIntentos, Duration backoffBase);

    /**
     * Deletes delivered and given-up rows older than {@code retencion} and returns how many went.
     * It also forgets their dedup key: the same drop to the same price, a retention later, queues again.
     */
    int purgarTerminadas(Duration retencion);
}
