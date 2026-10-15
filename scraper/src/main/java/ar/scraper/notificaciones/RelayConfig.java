package ar.scraper.notificaciones;

import java.time.Duration;

/**
 * @param lote          recipients (mails) claimed per batch, not rows: a recipient brings all their drops
 * @param maxPorMinuto  cap on mails per rolling minute for this process; 0 means no cap
 * @param retencion     how long delivered or given-up rows are kept
 * @param cadaPurga     minimum time between two retention purges
 */
public record RelayConfig(int lote, int maxIntentos, Duration lease, Duration backoffBase,
                          int maxPorMinuto, Duration retencion, Duration cadaPurga) {

    public static RelayConfig porDefecto() {
        return new RelayConfig(200, 5, Duration.ofSeconds(300), Duration.ofSeconds(60),
                0, Duration.ofDays(30), Duration.ofHours(6));
    }

    public RelayConfig conLote(int nuevo) {
        return new RelayConfig(nuevo, maxIntentos, lease, backoffBase, maxPorMinuto, retencion, cadaPurga);
    }

    public RelayConfig conMaxPorMinuto(int nuevo) {
        return new RelayConfig(lote, maxIntentos, lease, backoffBase, nuevo, retencion, cadaPurga);
    }
}
