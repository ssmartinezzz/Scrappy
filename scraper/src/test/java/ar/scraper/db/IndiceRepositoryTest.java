package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndicePort;
import ar.scraper.indices.PuntoIndice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V33 — {@code indice_valor} upsert by (indice, fecha), read back ascending. */
class IndiceRepositoryTest extends PostgresTestBase {

    private IndicePort repository;

    @BeforeEach
    void setUp() {
        repository = new IndiceRepository(dataSource());
    }

    @Test
    void guardarYLeerRoundTripeaEnOrdenAscendente() {
        repository.guardar(List.of(
                new PuntoIndice(Indice.IPC, LocalDate.parse("2026-02-28"), 110.0),
                new PuntoIndice(Indice.IPC, LocalDate.parse("2026-01-31"), 100.0)));

        List<PuntoIndice> serie = repository.serie(Indice.IPC);

        assertThat(serie).extracting(PuntoIndice::fecha)
                .containsExactly(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"));
        assertThat(serie).extracting(PuntoIndice::valor).containsExactly(100.0, 110.0);
    }

    @Test
    void guardarLaMismaFechaHaceUpsertNoDuplica() {
        repository.guardar(List.of(new PuntoIndice(Indice.IPC, LocalDate.parse("2026-01-31"), 100.0)));
        repository.guardar(List.of(new PuntoIndice(Indice.IPC, LocalDate.parse("2026-01-31"), 105.5)));

        List<PuntoIndice> serie = repository.serie(Indice.IPC);

        assertThat(serie).hasSize(1);
        assertThat(serie.get(0).valor()).isEqualTo(105.5);
    }

    @Test
    void unIndiceSinDatosDevuelveListaVacia() {
        assertThat(repository.serie(Indice.USD_OFICIAL)).isEmpty();
    }

    @Test
    void dosIndicesNoSeMezclan() {
        repository.guardar(List.of(
                new PuntoIndice(Indice.IPC, LocalDate.parse("2026-01-31"), 100.0),
                new PuntoIndice(Indice.USD_OFICIAL, LocalDate.parse("2026-01-31"), 1500.0)));

        assertThat(repository.serie(Indice.IPC)).extracting(PuntoIndice::valor).containsExactly(100.0);
        assertThat(repository.serie(Indice.USD_OFICIAL)).extracting(PuntoIndice::valor).containsExactly(1500.0);
    }
}
