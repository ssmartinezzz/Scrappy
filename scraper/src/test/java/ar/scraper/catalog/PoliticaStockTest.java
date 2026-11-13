package ar.scraper.catalog;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PoliticaStock — one owner of the read-time stock rule")
class PoliticaStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private final PoliticaStock politica =
            new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    private static Product producto(String url, Boolean enStock, Instant chequeado) {
        return Product.builder().sitio("S").nombre("N " + url).precio(1).url(url).talles(List.of())
                .enStock(enStock).stockChequeadoAt(chequeado).build();
    }

    @Test
    @DisplayName("estado uses the injected clock and vigencia")
    void estadoUsesClock() {
        assertThat(politica.estado(producto("a", true, AHORA.minus(Duration.ofDays(6))))).isEqualTo(EstadoStock.EN_STOCK);
        assertThat(politica.estado(producto("b", true, AHORA.minus(Duration.ofDays(8))))).isEqualTo(EstadoStock.DESCONOCIDO);
    }

    @Test
    @DisplayName("corte is now minus vigencia")
    void corte() {
        assertThat(politica.corte()).isEqualTo(AHORA.minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("candidatos drops confirmed sold-out rows by default, keeps unknown and stale ones")
    void candidatosDropsOnlyConfirmedSoldOut() {
        Product enStock = producto("en", true, AHORA.minusSeconds(60));
        Product sin = producto("sin", false, AHORA.minusSeconds(60));
        Product sinViejo = producto("sin-viejo", false, AHORA.minus(Duration.ofDays(30)));
        Product desconocido = producto("desc", null, null);

        assertThat(politica.candidatos(List.of(enStock, sin, sinViejo, desconocido), false))
                .containsExactly(enStock, sinViejo, desconocido);
    }

    @Test
    @DisplayName("candidatos with incluirSinStock keeps everything")
    void candidatosOptIn() {
        Product sin = producto("sin", false, AHORA.minusSeconds(60));
        assertThat(politica.candidatos(List.of(sin), true)).containsExactly(sin);
    }
}
