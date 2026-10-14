package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.Uso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V48 — {@code preferencia_armador.gastar_todo} round-trips and defaults to false. */
class PreferenciaArmadorGastarTodoRepositoryTest extends PostgresTestBase {

    private PreferenciaArmadorPort repository;

    @BeforeEach
    void setUp() {
        repository = new PreferenciaArmadorRepository(dataSource());
    }

    @Test
    void gastarTodoTrueRoundTripea() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, 3_000_000.0, false,
                PreferenciasDeArmado.NINGUNA, Uso.GAMING, true));

        assertThat(repository.cargar(usuario)).isPresent().get()
                .satisfies(p -> assertThat(p.gastarTodo()).isTrue());
    }

    @Test
    void sinPedirloQuedaEnFalse() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, 3_000_000.0, false));

        assertThat(repository.cargar(usuario)).isPresent().get()
                .satisfies(p -> assertThat(p.gastarTodo()).isFalse());
    }

    @Test
    void unSegundoGuardarPuedeApagarlo() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, null, false,
                PreferenciasDeArmado.NINGUNA, Uso.GAMING, true));

        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, null, false));

        assertThat(repository.cargar(usuario)).isPresent().get()
                .satisfies(p -> assertThat(p.gastarTodo()).isFalse());
    }
}
