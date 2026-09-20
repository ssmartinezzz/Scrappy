package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CriterioPorEjesTecnicos} — ejes técnicos del slot, luego precio
 * asc, luego url asc. Reemplaza a {@code CriterioScoreMlPrecioUrl}, que
 * rankeaba por {@code baseMlScore} (un percentil de PRECIO) y por eso
 * elegía siempre lo más barato de su categoría (pc-builder-gama T3b).
 */
@DisplayName("CriterioPorEjesTecnicos — ejes técnicos, luego precio asc, luego url asc")
class CriterioPorEjesTecnicosTest {

    private Product ram(String nombre, double precio, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                "RAM", "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    @Test
    @DisplayName("prefers more capacity within the same DDR generation, over a cheaper lower-capacity candidate")
    void prefiereMasCapacidadSobreMasBarato() {
        CriterioDeSeleccion criterio = new CriterioPorEjesTecnicos(EjesTecnicos.RAM);
        Product barata = ram("Memoria RAM Corsair DDR4 16GB", 10_000, "https://t/16gb");
        Product cara = ram("Memoria RAM Corsair DDR4 32GB", 20_000, "https://t/32gb");

        Product elegido = criterio.elegir(List.of(barata, cara));

        assertThat(elegido.url()).isEqualTo("https://t/32gb");
    }

    @Test
    @DisplayName("ties on the technical axis break by price ascending")
    void empataEnElEjeTecnicoYDesempataPorPrecio() {
        CriterioDeSeleccion criterio = new CriterioPorEjesTecnicos(EjesTecnicos.RAM);
        Product cara = ram("Memoria RAM Corsair DDR4 16GB Cara", 20_000, "https://t/cara");
        Product barata = ram("Memoria RAM Corsair DDR4 16GB Barata", 10_000, "https://t/barata");

        Product elegido = criterio.elegir(List.of(cara, barata));

        assertThat(elegido.url()).isEqualTo("https://t/barata");
    }

    @Test
    @DisplayName("ties on axis and price break by url ascending")
    void empataTodoYDesempataPorUrl() {
        CriterioDeSeleccion criterio = new CriterioPorEjesTecnicos(EjesTecnicos.RAM);
        Product b = ram("Memoria RAM Corsair DDR4 16GB B", 10_000, "https://t/ram-b");
        Product a = ram("Memoria RAM Corsair DDR4 16GB A", 10_000, "https://t/ram-a");

        Product elegido = criterio.elegir(List.of(b, a));

        assertThat(elegido.url()).isEqualTo("https://t/ram-a");
    }

    @Test
    @DisplayName("with the gabinete axis (empty), only price/url ever decides")
    void gabineteSinEjesSoloDecidePrecio() {
        CriterioDeSeleccion criterio = new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE);
        Product grande = new Product("TestSitio", "Gabinete Corsair 4000D ATX", 90_000, null,
                "https://t/atx", "https://img/test.jpg", "Gabinete", "", List.of(),
                Product.MlScore.EMPTY, "", "tecnologia", false);
        Product chico = new Product("TestSitio", "Gabinete NR200P ITX", 70_000, null,
                "https://t/itx", "https://img/test.jpg", "Gabinete", "", List.of(),
                Product.MlScore.EMPTY, "", "tecnologia", false);

        Product elegido = criterio.elegir(List.of(grande, chico));

        assertThat(elegido.url()).isEqualTo("https://t/itx"); // más barato, pese a ser más chico
    }
}
