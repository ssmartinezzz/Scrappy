package ar.scraper.json;

import ar.scraper.catalog.PoliticaStock;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductJson — stock derivado en lectura")
class ProductJsonEstadoStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private final PoliticaStock politica = new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    private static Product.ProductBuilder base() {
        return Product.builder().sitio("S").nombre("N").precio(1).url("u").talles(List.of());
    }

    private ObjectNode escribir(Product p) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        ProductJson.escribir(n, p, politica);
        return n;
    }

    @Test
    @DisplayName("un chequeo fresco true es en_stock, false es sin_stock")
    void fresh() {
        Instant hace1h = AHORA.minus(Duration.ofHours(1));
        assertThat(escribir(base().enStock(true).stockChequeadoAt(hace1h).build()).get("stock").asText())
                .isEqualTo("en_stock");
        assertThat(escribir(base().enStock(false).stockChequeadoAt(hace1h).build()).get("stock").asText())
                .isEqualTo("sin_stock");
    }

    @Test
    @DisplayName("un chequeo viejo se lee desconocido aunque lo guardado sea true; el crudo no se toca")
    void staleReadsUnknownButRawIsKept() {
        ObjectNode n = escribir(base().enStock(true)
                .vistoEnStockAt(AHORA.minus(Duration.ofDays(9)))
                .stockChequeadoAt(AHORA.minus(Duration.ofDays(9))).build());

        assertThat(n.get("stock").asText()).isEqualTo("desconocido");
        assertThat(n.get("enStock").asBoolean()).isTrue();
        assertThat(n.get("stockChequeadoAt").asText()).isEqualTo("2026-11-04T12:00:00Z");
    }

    @Test
    @DisplayName("sin señal es desconocido")
    void unknown() {
        assertThat(escribir(base().build()).get("stock").asText()).isEqualTo("desconocido");
    }
}
