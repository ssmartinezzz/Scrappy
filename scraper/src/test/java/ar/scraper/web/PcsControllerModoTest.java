package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.api.ApiException;
import ar.scraper.catalog.Facets;
import ar.scraper.db.DatabaseService;
import ar.scraper.pcs.ModoPresupuesto;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PcsController — modo de presupuesto")
class PcsControllerModoTest {

    private PcBuilder pcBuilder;
    private PcsController controller;

    @BeforeEach
    void setUp() {
        SujetoDePrueba.entrar("ADMIN");
        ScraperService service = mock(ScraperService.class);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.of(), Map.of(), Map.of(), facets, 0, 0));
        DatabaseService db = mock(DatabaseService.class);
        pcBuilder = spy(new PcBuilder());
        controller = new PcsController(service, pcBuilder, db.pcsGuardadas(), db.preferenciaArmador(),
                new ar.scraper.security.ActorResolver());
    }

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    private void builderConModo(String modo) {
        controller.builder(0, false, "", "", "", "", "", "", null, null, null, "", "", null, "", null, modo);
    }

    @Test
    @DisplayName("modo=gastar reaches the builder as GASTAR_TODO")
    void gastarLlegaAlBuilder() {
        builderConModo("gastar");

        verify(pcBuilder).armar(any(), anyDouble(), anyBoolean(), any(), any(), any(), any(),
                eq(ModoPresupuesto.GASTAR_TODO));
    }

    @Test
    @DisplayName("an absent modo reaches the builder as AHORRAR")
    void sinModoEsAhorrar() {
        builderConModo("");

        verify(pcBuilder).armar(any(), anyDouble(), anyBoolean(), any(), any(), any(), any(),
                eq(ModoPresupuesto.AHORRAR));
    }

    @Test
    @DisplayName("an unknown modo is a 400")
    void modoInvalidoEs400() {
        ApiException e = Wire.apiError(() -> builderConModo("todo"));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
    }
}
