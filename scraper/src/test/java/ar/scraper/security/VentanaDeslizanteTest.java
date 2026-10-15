package ar.scraper.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VentanaDeslizante — at most N per key per window, and no key outlives its window")
class VentanaDeslizanteTest {

    private static final class RelojMovil extends Clock {
        private Instant ahora = Instant.parse("2026-10-14T12:00:00Z");

        void avanzar(Duration d) { ahora = ahora.plus(d); }

        @Override public ZoneId getZone()            { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant()           { return ahora; }
    }

    @Test
    @DisplayName("admits up to the ceiling per key, refuses past it, and keys are independent")
    void tope() {
        VentanaDeslizante v = new VentanaDeslizante(new RelojMovil(), Duration.ofHours(1));

        for (int i = 0; i < 3; i++) {
            assertThat(v.registrar("a", 3)).isTrue();
        }
        assertThat(v.registrar("a", 3)).isFalse();
        assertThat(v.registrar("b", 3)).isTrue();
    }

    @Test
    @DisplayName("the window slides: a slot frees up once its own hour has passed")
    void desliza() {
        RelojMovil reloj = new RelojMovil();
        VentanaDeslizante v = new VentanaDeslizante(reloj, Duration.ofHours(1));
        v.registrar("a", 1);
        reloj.avanzar(Duration.ofMinutes(59));
        assertThat(v.registrar("a", 1)).isFalse();

        reloj.avanzar(Duration.ofMinutes(2));

        assertThat(v.registrar("a", 1)).isTrue();
    }

    @Test
    @DisplayName("invented keys are swept once their window expires")
    void barre() {
        RelojMovil reloj = new RelojMovil();
        VentanaDeslizante v = new VentanaDeslizante(reloj, Duration.ofHours(1));
        for (int i = 0; i < 500; i++) {
            v.registrar("k" + i, 1);
        }
        assertThat(v.claves()).isEqualTo(500);

        reloj.avanzar(Duration.ofHours(2));
        v.registrar("nueva", 1);

        assertThat(v.claves()).isEqualTo(1);
    }
}
