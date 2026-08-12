package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `sql-catalog-filtering` — el orden de `/api/data`, incluidos los DOS bugs
 * que el filtrado en memoria arrastraba y que este cambio arregla en vez de
 * replicar.
 *
 * <ol>
 *   <li><b>`ML Score` mostraba los peores primero.</b> El comparador ordenaba
 *       {@code scoreP} ascendente. El selector del frontend se llama "ML Score"
 *       y el score es una medida de OPORTUNIDAD: más alto es mejor.</li>
 *   <li><b>`% Oferta` filtraba adentro del sort.</b> Descartaba los productos
 *       sin descuento, así que cambiar SOLO el orden cambiaba el total y la
 *       cantidad de páginas. Ordenar no puede hacer desaparecer productos.</li>
 * </ol>
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("Ordering, with the two in-memory bugs fixed")
@DisplayName("/api/data — orden por SQL")
class CatalogOrdenTest extends PostgresTestBase {

    private DatabaseService db;
    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        repo = new CatalogQueryRepository(dataSource());
        db.upsertProductos(List.of(
                producto("https://s.com/barato", 100, 30, null),
                producto("https://s.com/caro", 9000, 90, null),
                producto("https://s.com/medio", 500, 60, "$1000"),
                producto("https://s.com/oferton", 700, 45, "$2800")
        ));
    }

    @Test
    @DisplayName("ML Score devuelve el MEJOR score primero, no el peor")
    void mlScoreOrdenaDescendente() {
        List<String> urls = urls("composite");

        assertThat(urls).startsWith("https://s.com/caro");
        assertThat(urls).endsWith("https://s.com/barato");
    }

    @Test
    @DisplayName("% Oferta ordena por descuento pero NO descarta lo que no tiene")
    void ofertaOrdenaSinFiltrar() {
        CatalogPage p = repo.buscar(CatalogFilter.todo(), "desc_pct", 1, 50);

        // 75% (2800 -> 700) antes que 50% (1000 -> 500); los sin descuento quedan
        // al final, pero SIGUEN estando.
        assertThat(p.productos().stream().map(Product::url).toList())
                .startsWith("https://s.com/oferton", "https://s.com/medio");
        assertThat(p.total())
                .as("cambiar el orden no puede cambiar cuántos productos hay")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("El total no depende del orden elegido")
    void elTotalEsIndependienteDelOrden() {
        for (String orden : List.of("precio_asc", "precio_desc", "nombre_asc", "composite", "desc_pct")) {
            assertThat(repo.buscar(CatalogFilter.todo(), orden, 1, 50).total())
                    .as("total con orden=%s", orden)
                    .isEqualTo(4);
        }
    }

    @Test
    @DisplayName("Precio ascendente y descendente son inversos")
    void precioAscYDesc() {
        assertThat(urls("precio_asc")).containsExactly(
                "https://s.com/barato", "https://s.com/medio", "https://s.com/oferton", "https://s.com/caro");
        assertThat(urls("precio_desc")).containsExactly(
                "https://s.com/caro", "https://s.com/oferton", "https://s.com/medio", "https://s.com/barato");
    }

    private List<String> urls(String orden) {
        return repo.buscar(CatalogFilter.todo(), orden, 1, 50)
                .productos().stream().map(Product::url).toList();
    }

    private Product producto(String url, double precio, int score, String precioOrig) {
        Product.MlScore ml = new Product.MlScore(score, List.of(), false, "estable", score, 0.0, "standard");
        return new Product("Sitio", "Producto " + url, precio, precioOrig, url,
                "http://img.example/x.jpg", "Remera", "unisex", List.of("M"), ml, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }
}
