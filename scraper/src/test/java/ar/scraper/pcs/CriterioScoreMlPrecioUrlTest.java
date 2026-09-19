package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CriterioScoreMlPrecioUrl — rank -baseMlScore desc, precio asc, url asc")
class CriterioScoreMlPrecioUrlTest {

    private final CriterioDeSeleccion criterio =
            new CriterioScoreMlPrecioUrl(new RecommendationService());

    private Product producto(String nombre, double precio, String url, int scoreP) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                "RAM", "", List.of(), ml, "", "tecnologia", false);
    }

    @Test
    @DisplayName("prefers the higher baseMlScore (lower scoreP) even at a higher price")
    void prefiereMejorScoreMl() {
        Product barata = producto("Barata", 10_000, "https://t/ram-barata", 90);
        Product cara = producto("Cara", 20_000, "https://t/ram-cara", 10);

        Product elegido = criterio.elegir(List.of(barata, cara));

        assertThat(elegido.url()).isEqualTo("https://t/ram-cara");
    }

    @Test
    @DisplayName("ties on baseMlScore break by price ascending")
    void empataPorScoreYDesempataPorPrecio() {
        Product cara = producto("Cara", 20_000, "https://t/ram-cara", 50);
        Product barata = producto("Barata", 10_000, "https://t/ram-barata", 50);

        Product elegido = criterio.elegir(List.of(cara, barata));

        assertThat(elegido.url()).isEqualTo("https://t/ram-barata");
    }

    @Test
    @DisplayName("ties on score and price break by url ascending")
    void empataPorScoreYPrecioYDesempataPorUrl() {
        Product b = producto("B", 10_000, "https://t/ram-b", 50);
        Product a = producto("A", 10_000, "https://t/ram-a", 50);

        Product elegido = criterio.elegir(List.of(b, a));

        assertThat(elegido.url()).isEqualTo("https://t/ram-a");
    }
}
