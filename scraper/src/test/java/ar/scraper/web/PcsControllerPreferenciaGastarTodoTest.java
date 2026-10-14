package ar.scraper.web;

import ar.scraper.db.DatabaseService;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.Uso;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code gastarTodo} on {@code PUT}/{@code GET /api/pcs/preferencia}; absent means false. */
class PcsControllerPreferenciaGastarTodoTest {

    private PreferenciaArmadorPort preferenciaArmador;
    private PcsController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        DatabaseService db = mock(DatabaseService.class);
        preferenciaArmador = mock(PreferenciaArmadorPort.class);
        when(db.preferenciaArmador()).thenReturn(preferenciaArmador);
        SujetoDePrueba.entrar("ADMIN");
        controller = new PcsController(mock(ScraperService.class), new ar.scraper.pcs.PcBuilder(),
                db.pcsGuardadas(), db.preferenciaArmador(), new ar.scraper.security.ActorResolver());
    }

    @Test
    void putConGastarTodoLoPersisteYLoDevuelve() {
        ResponseEntity<?> resp = controller.putPreferencia(Map.of("gama", "alta", "gastarTodo", true));

        assertThat(Wire.data(resp).get("gastarTodo").asBoolean()).isTrue();
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(Gama.ALTA, null, false,
                PreferenciasDeArmado.NINGUNA, Uso.GAMING, true)));
    }

    @Test
    void putSinGastarTodoEsFalse() {
        ResponseEntity<?> resp = controller.putPreferencia(Map.of("gama", "alta"));

        assertThat(Wire.data(resp).get("gastarTodo").asBoolean()).isFalse();
        verify(preferenciaArmador).guardar(any(), eq(new PreferenciaArmador(Gama.ALTA, null, false)));
    }

    @Test
    void getDevuelveGastarTodo() {
        when(preferenciaArmador.cargar(any())).thenReturn(Optional.of(new PreferenciaArmador(
                Gama.ALTA, null, false, PreferenciasDeArmado.NINGUNA, Uso.GAMING, true)));

        JsonNode body = Wire.data(controller.getPreferencia());

        assertThat(body.get("gastarTodo").asBoolean()).isTrue();
    }
}
