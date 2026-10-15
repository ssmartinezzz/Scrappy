package ar.scraper.notificaciones;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class RelojDePrueba extends Clock {

    private Instant ahora = Instant.parse("2026-10-14T12:00:00Z");

    void avanzar(Duration d) {
        ahora = ahora.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
