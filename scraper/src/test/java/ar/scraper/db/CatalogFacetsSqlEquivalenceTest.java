package ar.scraper.db;

import ar.scraper.aggregator.FacetCalculator;
import ar.scraper.aggregator.ResultAggregator;
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
 * `sql-catalog-filtering` — las facetas por GROUP BY tienen que dar lo mismo
 * que el barrido en memoria que reemplazan.
 *
 * <p>Mismo dataset, dos caminos: {@link FacetCalculator} sobre la lista y
 * {@link CatalogQueryRepository#facetas()} sobre la base. Las claves y los
 * conteos tienen que coincidir exactamente — incluida la normalización menos
 * obvia: género en minúscula, categoría capitalizada, y un producto contando
 * UNA VEZ POR BADGE que tiene, no una sola vez.</p>
 *
 * <p>Donde el orden es una decisión de producto (talles, categorías, marcas,
 * subcategorías) se compara el orden también. Donde el orden en memoria era el
 * accidente de "primera aparición en un catálogo ordenado por precio", se
 * compara solo el contenido: ahí el SQL usa conteo descendente a propósito.</p>
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("SQL facets match the in-memory ones")
@DisplayName("/api/facets — GROUP BY equivalente al barrido en memoria")
class CatalogFacetsSqlEquivalenceTest extends PostgresTestBase {

    private CatalogQueryRepository repo;
    private ResultAggregator.Facets enMemoria;
    private ResultAggregator.Facets enSql;

    @BeforeEach
    void setUp() {
        DatabaseService db = new DatabaseService(dataSource());
        repo = new CatalogQueryRepository(dataSource());
        List<Product> dataset = dataset();
        db.upsertProductos(dataset);

        enMemoria = FacetCalculator.calcular(dataset);
        enSql = repo.facetas();
    }

    @Test
    @DisplayName("Talles: mismas claves, mismos conteos y el mismo orden no alfabético")
    void talles() {
        assertThat(enSql.talles()).containsExactlyEntriesOf(enMemoria.talles());
    }

    @Test
    @DisplayName("Badges: un producto cuenta una vez POR BADGE")
    void badges() {
        assertThat(enSql.badges()).containsExactlyInAnyOrderEntriesOf(enMemoria.badges());
        assertThat(enSql.badges().get("trending")).isEqualTo(2L);
    }

    @Test
    @DisplayName("Categorías: capitalizadas igual, ordenadas por conteo")
    void categorias() {
        assertThat(enSql.categorias()).containsExactlyEntriesOf(enMemoria.categorias());
    }

    @Test
    @DisplayName("Marcas y subcategorías conservan su orden propio")
    void marcasYSubcategorias() {
        assertThat(enSql.marcas()).containsExactlyEntriesOf(enMemoria.marcas());
        assertThat(enSql.subCategorias()).containsExactlyEntriesOf(enMemoria.subCategorias());
    }

    @Test
    @DisplayName("Género y atributos visuales: mismo contenido")
    void generoYVisuales() {
        assertThat(enSql.generos()).containsExactlyInAnyOrderEntriesOf(enMemoria.generos());
        assertThat(enSql.fits()).containsExactlyInAnyOrderEntriesOf(enMemoria.fits());
        assertThat(enSql.estampados()).containsExactlyInAnyOrderEntriesOf(enMemoria.estampados());
        assertThat(enSql.escotes()).containsExactlyInAnyOrderEntriesOf(enMemoria.escotes());
        assertThat(enSql.colorDominantes()).containsExactlyInAnyOrderEntriesOf(enMemoria.colorDominantes());
    }

    @Test
    @DisplayName("El resumen da el rango de precios y el conteo por sitio del catálogo persistido")
    void resumen() {
        CatalogQueryRepository.Resumen r = repo.resumen();

        assertThat(r.minPrecio()).isEqualTo(1500.0);
        assertThat(r.maxPrecio()).isEqualTo(45000.0);
        assertThat(r.porSitio()).containsEntry("Freres", 2L).containsEntry("VCP", 1L);
    }

    // ─── dataset ────────────────────────────────────────────────────────────

    private List<Product> dataset() {
        return List.of(
                producto("https://s.com/1", "Remera", 1500, "Freres", "Remeras", "hombre",
                        List.of("S", "M"), List.of("trending"), "Nike", "Basicas", "regular"),
                producto("https://s.com/2", "Remera 2", 2500, "Freres", "REMERAS", "hombre",
                        List.of("XL", "38"), List.of("trending", "below_market"), "Nike", "Basicas", ""),
                producto("https://s.com/3", "Zapatilla", 45000, "VCP", "Zapatillas", "mujer",
                        List.of("40"), List.of(), "Puma", "Running", "oversize")
        );
    }

    private Product producto(String url, String nombre, double precio, String sitio, String categoria,
                             String genero, List<String> talles, List<String> badges, String marca,
                             String subCategoria, String fit) {
        Product.MlScore ml = new Product.MlScore(70, badges, false, "estable", 50, 0.0, "standard");
        return new Product(sitio, nombre, precio, null, url, "http://img.example/x.jpg",
                categoria, genero, talles, ml, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, subCategoria,
                new Product.VisualAttrs(fit, "", "", ""));
    }
}
