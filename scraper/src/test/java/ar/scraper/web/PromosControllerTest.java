package ar.scraper.web;

import ar.scraper.catalog.PromoPagina;
import ar.scraper.catalog.PromoQueryPort;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoVigente;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PromosController — GET /api/promos")
class PromosControllerTest {

    private final PromoQueryPort port = mock(PromoQueryPort.class);
    private final PromosController controller = new PromosController(port);

    private static PromoVigente macroVisa() {
        return new PromoVigente("Sporting", "Macro", TipoMedio.BANCO, "Visa", TipoPromo.CUOTAS_SIN_INTERES, 12, null,
                Alcance.SITIO, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "12 sin interés con Macro (Visa)",
                Instant.parse("2026-11-13T15:00:00Z"));
    }

    @Test
    @DisplayName("serializes medio {nombre,tipo}, red {nombre} or null, kind, installments, pct, scope, vigencia, text and UTC last-seen")
    void shape() {
        when(port.buscar(any(), any(), anyInt(), anyInt())).thenReturn(new PromoPagina(List.of(macroVisa(),
                new PromoVigente("Sporting", "Transferencia", TipoMedio.MEDIO, null, TipoPromo.DESCUENTO, null,
                        new BigDecimal("10"), Alcance.SITIO, null, null, null, Instant.parse("2026-11-13T15:00:00Z"))), 2));

        var resp = controller.listar(null, null, 0, 50);

        JsonNode p = Wire.data(resp).get(0);
        assertThat(p.path("sitio").asText()).isEqualTo("Sporting");
        assertThat(p.path("medio").path("nombre").asText()).isEqualTo("Macro");
        assertThat(p.path("medio").path("tipo").asText()).isEqualTo("banco");
        assertThat(p.path("red").path("nombre").asText()).isEqualTo("Visa");
        assertThat(p.path("tipo").asText()).isEqualTo("cuotas_sin_interes");
        assertThat(p.path("cuotas").asInt()).isEqualTo(12);
        assertThat(p.path("pct").isNull()).isTrue();
        assertThat(p.path("alcance").asText()).isEqualTo("sitio");
        assertThat(p.path("vigenciaDesde").asText()).isEqualTo("2026-11-01");
        assertThat(p.path("vigenciaHasta").asText()).isEqualTo("2026-11-30");
        assertThat(p.path("textoFuente").asText()).isEqualTo("12 sin interés con Macro (Visa)");
        assertThat(p.path("vistoAt").asText()).isEqualTo("2026-11-13T15:00:00Z");
        JsonNode t = Wire.data(resp).get(1);
        assertThat(t.path("red").isNull()).isTrue();
        assertThat(t.path("pct").asDouble()).isEqualTo(10.0);
        assertThat(t.path("vigenciaDesde").isNull()).isTrue();
    }

    @Test
    @DisplayName("the page envelope carries number, size, total and totalPages; filters reach the port")
    void pageAndFilters() {
        when(port.buscar(eq("Sporting"), eq("Visa"), eq(1), eq(10))).thenReturn(new PromoPagina(List.of(macroVisa()), 11));

        var resp = controller.listar("Sporting", "Visa", 1, 10);

        assertThat(Wire.page(resp).path("number").asInt()).isEqualTo(1);
        assertThat(Wire.page(resp).path("total").asInt()).isEqualTo(11);
        assertThat(Wire.page(resp).path("totalPages").asInt()).isEqualTo(2);
        verify(port).buscar("Sporting", "Visa", 1, 10);
    }

    @Test
    @DisplayName("size is clamped to 1..200 and a negative page is page 0")
    void clamps() {
        when(port.buscar(any(), any(), anyInt(), anyInt())).thenReturn(new PromoPagina(List.of(), 0));

        controller.listar(null, null, -3, 100000);
        controller.listar(null, null, 0, 0);

        verify(port).buscar(null, null, 0, 200);
        verify(port).buscar(null, null, 0, 1);
    }
}
