package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mejores picks — stock por pick")
class MarcasPicksStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private final PoliticaStock politica = new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    private static Product ssd(String nombre, Boolean enStock, Instant chequeado) {
        return Product.builder().sitio("S").nombre(nombre).precio(10000).url("https://t/" + nombre.replace(' ', '-'))
                .imagenUrl("img").categoria("Almacenamiento").genero("unisex").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Kingston").rubro("tecnologia")
                .enStock(enStock).stockChequeadoAt(chequeado).vistoEnStockAt(chequeado).build();
    }

    private AggregatedResult catalogo(Product... ps) {
        return new AggregatedResult(List.of(ps), Map.of(), Map.of(), null, 0, 0);
    }

    @Test
    @DisplayName("mejores: a pick carries its derived stock and timestamps")
    void mejoresPicksCarryStock() {
        var out = MarcasPicksView.mejores(catalogo(ssd("SSD A", false, AHORA.minusSeconds(60))),
                        "tecnologia", false, "", politica)
                .stream().flatMap(c -> c.getPicks().stream()).toList();

        assertThat(out).isNotEmpty().allSatisfy(p -> {
            assertThat(p.getStock()).isEqualTo("sin_stock");
            assertThat(p.getStockChequeadoAt()).isEqualTo("2026-11-13T11:59:00Z");
        });
    }

    @Test
    @DisplayName("paged picks carry stock too; stale reads unknown")
    void pagedPicksCarryStock() {
        var ranking = MarcasPicksView.ranking(catalogo(ssd("SSD B", true, AHORA.minus(Duration.ofDays(30)))),
                "tecnologia", false, "").get("Almacenamiento");

        MarcasPicksDtos.PicksPagina pagina = MarcasPicksView.pagina(ranking, null, 24, politica);

        assertThat(pagina.getPicks()).hasSize(1);
        assertThat(pagina.getPicks().get(0).getStock()).isEqualTo("desconocido");
    }
}
