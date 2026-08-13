package ar.scraper.db;

import ar.scraper.aggregator.normalize.BrandExtractor;
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
 * close-1nf-and-3nf-foundation, decision D5 — the half of V19 that shipped
 * unexercised.
 *
 * <p>V19 stopped {@code BrandExtractor} falling back to the site name when no
 * curated brand matches, so a store name is no longer offered as a brand and a
 * bookmarked {@code ?marca=<store>} returns empty. That consequence was written
 * down but never tested, and the gap had a cost: the spec illustrated it with
 * {@code ?marca=Fuark}, which is <em>wrong</em> — {@code Fuark} is
 * simultaneously a site AND a legitimately curated entry in
 * {@code BrandExtractor.MARCAS}, so V19 explicitly protects it (together with
 * {@code Bulks} and {@code Harvey}) and that filter keeps working. A test would
 * have caught the bad example immediately; review did not.</p>
 *
 * <p>{@code Midway} is the honest example: a configured site, absent from the
 * curated list. Both halves are pinned here — that the extractor abstains, and
 * that the catalog surfaces agree.</p>
 */
@Epic("Persistence")
@Feature("Brand")
@Story("A store name is never a brand, and never offered as one")
@DisplayName("marca — un nombre de tienda no es una marca (D5)")
class CatalogMarcaNombreDeSitioTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = new DatabaseService(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        db.upsertProductos(List.of(
                // Sold by Midway, no curated brand in the name -> abstains.
                producto("http://midway.test/remera", "Remera Lisa Algodón", "Midway",
                        new BrandExtractor().extraer("Remera Lisa Algodón", "Midway")),
                // Same store, but the name does carry a curated brand.
                producto("http://midway.test/nike", "Remera Nike Dri-Fit", "Midway",
                        new BrandExtractor().extraer("Remera Nike Dri-Fit", "Midway"))));
    }

    @Test
    @DisplayName("BrandExtractor abstiene con '' en vez de devolver el sitio")
    void extractorAbstiene() {
        BrandExtractor extractor = new BrandExtractor();

        assertThat(extractor.extraer("Remera Lisa Algodón", "Midway"))
                .as("sin marca curada en el nombre, el sitio NO es la respuesta")
                .isEmpty();
        assertThat(extractor.extraer("Remera Nike Dri-Fit", "Midway"))
                .as("una marca curada en el nombre sí gana")
                .isEqualTo("Nike");
    }

    @Test
    @DisplayName("?marca=Midway no devuelve nada: esa marca nunca existió")
    void filtrarPorNombreDeSitioNoDevuelveNada() {
        CatalogPage pagina = repo.buscar(
                CatalogFilter.todo().conMarcas(List.of("Midway")), "precio", 1, 50);

        assertThat(pagina.productos())
                .as("un filtro guardado con un nombre de tienda queda vacío, no cae de vuelta al sitio")
                .isEmpty();
    }

    @Test
    @DisplayName("Las facetas no ofrecen 'Midway' como marca, y sí ofrecen Nike")
    void facetasNoOfrecenElNombreDeSitio() {
        var marcas = repo.facetas().marcas();

        assertThat(marcas)
                .as("si la UI nunca la ofrece, el único modo de llegar al filtro vacío es un link viejo")
                .doesNotContainKey("Midway");
        assertThat(marcas).containsKey("Nike");
    }

    @Test
    @DisplayName("Fuark NO es el ejemplo: es sitio y marca curada a la vez")
    void fuarkSigueSiendoUnaMarcaValida() {
        assertThat(new BrandExtractor().extraer("Buzo Fuark Oversize", "Fuark"))
                .as("V19 protege Bulks/Fuark/Harvey justamente porque están en MARCAS")
                .isEqualTo("Fuark");
    }

    private Product producto(String url, String nombre, String sitio, String marca) {
        Product.MlScore ml = new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard");
        return new Product(sitio, nombre, 10000.0, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), ml, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }
}
