package ar.scraper.scrape;

import ar.scraper.scrape.UltimaCorrida.SitioFallido;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UltimaCorridaRegistroTest {

    private static UltimaCorrida corrida(SitioFallido... fallidos) {
        return new UltimaCorrida(UUID.randomUUID(), Instant.parse("2026-11-27T18:59:18Z"), List.of(fallidos));
    }

    @Test
    void aTableNameThatDiffersFromTheRegistryComesBackInTheRegistrySpelling() {
        // Real case: V95 seeded "Under Armour", the registry builds "Underarmour" from sitio.underarmour.
        var u = corrida(new SitioFallido("Under Armour", UltimaCorrida.ERROR_DEADLINE_GLOBAL, true));

        var r = u.conNombresDelRegistro(List.of("Nike", "Underarmour"));

        assertThat(r.fallidos()).containsExactly(
                new SitioFallido("Underarmour", UltimaCorrida.ERROR_DEADLINE_GLOBAL, true));
    }

    @Test
    void namesThatAlreadyMatchAndSitesGoneFromTheRegistryAreKept() {
        var u = corrida(
                new SitioFallido("Nike", "timeout", false),
                new SitioFallido("Sitio Dado De Baja", "HTTP 403", false));

        var r = u.conNombresDelRegistro(List.of("Nike"));

        assertThat(r.fallidos()).extracting(SitioFallido::sitio)
                .containsExactly("Nike", "Sitio Dado De Baja");
    }

    @Test
    void runIdentityIsUnchanged() {
        var u = corrida(new SitioFallido("Under Armour", "x", false));

        var r = u.conNombresDelRegistro(List.of("Underarmour"));

        assertThat(r.uuid()).isEqualTo(u.uuid());
        assertThat(r.finishedAt()).isEqualTo(u.finishedAt());
    }
}
