package ar.scraper.aggregator;

import ar.scraper.model.ScrapeResult;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDelSitio;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Aggregation & Grouping")
@Feature("Promos")
@DisplayName("ResultAggregator — which sites count as promo-read")
class ResultAggregatorPromosTest {

    private static final PromoFinanciacion DESCUENTO = new PromoFinanciacion("Transferencia", TipoMedio.MEDIO,
            TipoPromo.DESCUENTO, null, new BigDecimal("10"), Alcance.SITIO, null, null, "t");

    private static ScrapeResult resultado(String sitio, String error, PromosDelSitio promos) {
        return new ScrapeResult(sitio, List.of(), error, 1, List.of(), promos);
    }

    @Test
    @DisplayName("only sites that flagged a successful read and ended clean are read")
    void onlyCleanFlaggedSitesAreRead() {
        var promos = ResultAggregator.promosDe(List.of(
                resultado("Leido", null, PromosDelSitio.leidas(List.of(DESCUENTO))),
                resultado("LeidoVacio", null, PromosDelSitio.leidas(List.of())),
                resultado("SinExtractor", null, PromosDelSitio.NO_LEIDO),
                resultado("ConError", "boom", PromosDelSitio.leidas(List.of(DESCUENTO)))));

        assertThat(promos.sitiosLeidos()).containsExactlyInAnyOrder("Leido", "LeidoVacio");
        assertThat(promos.delSitio()).containsOnlyKeys("Leido");
    }

    @Test
    @DisplayName("a scraper that never mentions promos yields an empty carrier")
    void defaultResultYieldsEmpty() {
        assertThat(ResultAggregator.promosDe(List.of(new ScrapeResult("X", List.of(), null, 1))).esVacio())
                .isTrue();
    }
}
