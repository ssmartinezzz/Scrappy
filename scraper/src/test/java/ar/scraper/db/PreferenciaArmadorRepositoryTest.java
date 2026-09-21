package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V35 — {@code preferencia_armador}: one row per user, upserted. */
class PreferenciaArmadorRepositoryTest extends PostgresTestBase {

    private PreferenciaArmadorPort repository;

    @BeforeEach
    void setUp() {
        repository = new PreferenciaArmadorRepository(dataSource());
    }

    @Test
    void cargarDevuelveVacioParaUnUsuarioSinFila() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        assertThat(repository.cargar(usuario)).isEmpty();
    }

    @Test
    void guardarYCargarRoundTripeaLosTresCampos() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, 1500000.0, true));

        Optional<PreferenciaArmador> cargada = repository.cargar(usuario);
        assertThat(cargada).isPresent();
        assertThat(cargada.get().gama()).isEqualTo(Gama.ALTA);
        assertThat(cargada.get().presupuesto()).isEqualTo(1500000.0);
        assertThat(cargada.get().conGpu()).isTrue();
    }

    @Test
    void elPresupuestoNuloRoundTripea() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        repository.guardar(usuario, new PreferenciaArmador(Gama.MEDIA, null, false));

        assertThat(repository.cargar(usuario)).isPresent()
                .get()
                .satisfies(p -> assertThat(p.presupuesto()).isNull());
    }

    @Test
    void unSegundoGuardarSobreescribeLaMismaFila() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        repository.guardar(usuario, new PreferenciaArmador(Gama.BAJA, 300000.0, false));

        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, 2000000.0, true));

        Optional<PreferenciaArmador> cargada = repository.cargar(usuario);
        assertThat(cargada).isPresent();
        assertThat(cargada.get().gama()).isEqualTo(Gama.ALTA);
        assertThat(cargada.get().presupuesto()).isEqualTo(2000000.0);
        assertThat(cargada.get().conGpu()).isTrue();
        assertThat(filas(usuario))
                .as("guardar upserts — a second call must not create a second row")
                .isEqualTo(1);
    }

    @Test
    void laPreferenciaDeUnUsuarioEsInvisibleParaOtro() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        UUID otroUsuario = UsuarioDePrueba.crear(dataSource(), "otroUsuario");
        repository.guardar(usuario, new PreferenciaArmador(Gama.ALTA, 1000000.0, true));

        assertThat(repository.cargar(otroUsuario)).isEmpty();
    }

    @Test
    void guardarConGamaDesconocidaLanzaYNoEscribeNada() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        assertThatThrownBy(() -> repository.guardar(usuario, new PreferenciaArmador(Gama.DESCONOCIDA, null, false)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.cargar(usuario)).isEmpty();
    }

    @Test
    void gamaBajaRoundTripeaPorLaFilaEconomica() {
        UUID usuario = UsuarioDePrueba.yo(dataSource());

        repository.guardar(usuario, new PreferenciaArmador(Gama.BAJA, null, false));

        assertThat(repository.cargar(usuario)).isPresent()
                .get()
                .satisfies(p -> assertThat(p.gama()).isEqualTo(Gama.BAJA));
    }

    private int filas(UUID usuarioId) throws Exception {
        try (var c = dataSource().getConnection();
             var ps = c.prepareStatement("SELECT count(*) FROM preferencia_armador WHERE usuario_id = ?")) {
            ps.setObject(1, usuarioId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
