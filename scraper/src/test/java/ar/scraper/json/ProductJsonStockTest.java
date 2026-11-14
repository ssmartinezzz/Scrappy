package ar.scraper.json;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductJson — estado de stock")
class ProductJsonStockTest {

    private static Product.ProductBuilder base() {
        return Product.builder().sitio("S").nombre("N").precio(1).url("u").talles(List.of());
    }

    @Test
    @DisplayName("sin señal serializa null, nunca true")
    void unknownIsNull() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        ProductJson.escribir(n, base().build());
        assertThat(n.get("enStock").isNull()).isTrue();
        assertThat(n.get("vistoEnStockAt").isNull()).isTrue();
        assertThat(n.get("stockChequeadoAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("estado conocido: booleano y timestamps UTC ISO-8601")
    void knownState() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        ProductJson.escribir(n, base().enStock(false)
                .vistoEnStockAt(Instant.parse("2026-11-10T12:00:00Z"))
                .stockChequeadoAt(Instant.parse("2026-11-13T08:30:15.123Z")).build());
        assertThat(n.get("enStock").asBoolean(true)).isFalse();
        assertThat(n.get("vistoEnStockAt").asText()).isEqualTo("2026-11-10T12:00:00Z");
        assertThat(n.get("stockChequeadoAt").asText()).isEqualTo("2026-11-13T08:30:15Z");
    }
}
