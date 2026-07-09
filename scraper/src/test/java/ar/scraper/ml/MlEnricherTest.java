package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MlEnricher#enriquecer(List, JsonNode)}: wires
 * per-product ML scores (and an optional refined category) into a rebuilt
 * {@link Product}.
 */
@Epic("ML Pipeline")
@Feature("Score Enrichment")
@DisplayName("MlEnricher — wiring ML scores into Product")
class MlEnricherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void packProductPreservesCantidadUnidadesAfterEnrichment() throws Exception {
        // Regression for PR2: enriquecer() previously rebuilt Product via the
        // 16-arg legacy constructor, silently resetting cantidadUnidades to 1.
        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000.0, null, "https://site.com/pack",
                "", "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 3);

        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/pack": {
                            "composite": 60,
                            "badge": "precio_bajo",
                            "pctil": 40
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(pack), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(3);
        assertThat(result.get(0).esPack()).isTrue();
        assertThat(result.get(0).ml().badge()).isEqualTo("precio_bajo");
    }

    @Test
    void singleUnitProductStaysNonPackAfterEnrichment() throws Exception {
        Product single = new Product("Sitio", "Remera básica", 5000.0, null,
                "https://site.com/single", "", "Remeras", "unisex", List.of());

        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/single": { "composite": 50, "badge": "", "pctil": 50 }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(single), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(1);
        assertThat(result.get(0).esPack()).isFalse();
    }

    @Test
    void productWithoutMatchingScoreIsReturnedUnchanged() throws Exception {
        Product pack = new Product(
                "Sitio", "Pack x4 Medias", 8000.0, null, "https://site.com/sinmatch",
                "", "Medias", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 4);

        JsonNode mlOutput = MAPPER.readTree("""
                { "scores": {} }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(pack), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(pack);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(4);
    }
}
