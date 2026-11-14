package ar.scraper.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EstadoStock — read-time staleness, unknown is never in stock")
class EstadoStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private static final Duration VIGENCIA = Duration.ofDays(7);

    @Test
    @DisplayName("a fresh true check is EN_STOCK")
    void freshTrue() {
        assertThat(EstadoStock.de(true, AHORA.minus(Duration.ofDays(1)), AHORA, VIGENCIA))
                .isEqualTo(EstadoStock.EN_STOCK);
    }

    @Test
    @DisplayName("a fresh false check is SIN_STOCK")
    void freshFalse() {
        assertThat(EstadoStock.de(false, AHORA.minus(Duration.ofHours(2)), AHORA, VIGENCIA))
                .isEqualTo(EstadoStock.SIN_STOCK);
    }

    @Test
    @DisplayName("a stale check reads as DESCONOCIDO, true or false, never EN_STOCK")
    void staleIsUnknown() {
        Instant viejo = AHORA.minus(Duration.ofDays(8));
        assertThat(EstadoStock.de(true, viejo, AHORA, VIGENCIA)).isEqualTo(EstadoStock.DESCONOCIDO);
        assertThat(EstadoStock.de(false, viejo, AHORA, VIGENCIA)).isEqualTo(EstadoStock.DESCONOCIDO);
    }

    @Test
    @DisplayName("exactly at the vigencia boundary is still fresh; one second later is stale")
    void boundary() {
        assertThat(EstadoStock.de(true, AHORA.minus(VIGENCIA), AHORA, VIGENCIA)).isEqualTo(EstadoStock.EN_STOCK);
        assertThat(EstadoStock.de(true, AHORA.minus(VIGENCIA).minusSeconds(1), AHORA, VIGENCIA))
                .isEqualTo(EstadoStock.DESCONOCIDO);
    }

    @Test
    @DisplayName("null stock, or no check timestamp, is DESCONOCIDO")
    void nulls() {
        assertThat(EstadoStock.de(null, AHORA, AHORA, VIGENCIA)).isEqualTo(EstadoStock.DESCONOCIDO);
        assertThat(EstadoStock.de(true, null, AHORA, VIGENCIA)).isEqualTo(EstadoStock.DESCONOCIDO);
        assertThat(EstadoStock.de(false, null, AHORA, VIGENCIA)).isEqualTo(EstadoStock.DESCONOCIDO);
    }

    @Test
    @DisplayName("wire values are the API contract")
    void wire() {
        assertThat(EstadoStock.EN_STOCK.wire()).isEqualTo("en_stock");
        assertThat(EstadoStock.SIN_STOCK.wire()).isEqualTo("sin_stock");
        assertThat(EstadoStock.DESCONOCIDO.wire()).isEqualTo("desconocido");
    }
}
