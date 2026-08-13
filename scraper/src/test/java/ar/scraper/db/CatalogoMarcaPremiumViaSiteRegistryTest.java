package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 3 (design E2, coordinator-directed
 * third option). Real Postgres, {@code TEST-3}.
 *
 * <p>{@code marcaPremium} is resolved in {@link ProductRowMapper#map} from
 * {@code SiteRegistry.esPremium(SiteClassification.sitioKey(sitio))} — a
 * {@code HashMap} lookup against the already-loaded ~30-row registry, not a
 * SQL {@code LEFT JOIN} (measured at +28% on {@code cargarProductos()}, over
 * the pre-committed 5% threshold and therefore rejected) and not the stored
 * {@code productos.marca_premium} column. Uses {@code Harvey} (V18's seeded
 * premium site) and {@code Freres} (non-premium) — both already exist in the
 * migrated schema, no extra seeding needed.</p>
 */
@Epic("Catalog")
@Feature("Site registry")
@Story("marcaPremium is resolved in-memory from SiteRegistry, not a column or a join")
@DisplayName("ProductRowMapper — marcaPremium via SiteRegistry (real Postgres)")
class CatalogoMarcaPremiumViaSiteRegistryTest extends PostgresTestBase {

    @Test
    @DisplayName("un producto de un sitio premium (Harvey) sale con marcaPremium=true")
    void premiumSiteProductComesBackMarcaPremiumTrue() {
        DatabaseService db = new DatabaseService(dataSource());
        CatalogQueryRepository repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        db.upsertProductos(List.of(producto("https://catalogo-premium.test/a", "Harvey")));

        CatalogPage page = repo.buscar(CatalogFilter.todo(), "precio_asc", 1, 24);

        Product leido = page.productos().stream()
                .filter(p -> "https://catalogo-premium.test/a".equals(p.url()))
                .findFirst().orElseThrow();
        assertThat(leido.marcaPremium()).isTrue();
    }

    @Test
    @DisplayName("un producto de un sitio no premium (Freres) sale con marcaPremium=false")
    void nonPremiumSiteProductComesBackMarcaPremiumFalse() {
        DatabaseService db = new DatabaseService(dataSource());
        CatalogQueryRepository repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        db.upsertProductos(List.of(producto("https://catalogo-premium.test/b", "Freres")));

        CatalogPage page = repo.buscar(CatalogFilter.todo(), "precio_asc", 1, 24);

        Product leido = page.productos().stream()
                .filter(p -> "https://catalogo-premium.test/b".equals(p.url()))
                .findFirst().orElseThrow();
        assertThat(leido.marcaPremium()).isFalse();
    }

    @Test
    @DisplayName("un sitio desconocido (no sembrado en sitio) abstiene a marcaPremium=false")
    void unknownSiteAbstainsToMarcaPremiumFalse() {
        DatabaseService db = new DatabaseService(dataSource());
        CatalogQueryRepository repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        db.upsertProductos(List.of(producto("https://catalogo-premium.test/c", "SitioNuncaVisto")));

        CatalogPage page = repo.buscar(CatalogFilter.todo(), "precio_asc", 1, 24);

        Product leido = page.productos().stream()
                .filter(p -> "https://catalogo-premium.test/c".equals(p.url()))
                .findFirst().orElseThrow();
        assertThat(leido.marcaPremium()).isFalse();
    }

    private static Product producto(String url, String sitio) {
        return new Product(sitio, "Producto de prueba", 10000.0, null, url, "img",
                "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Nike", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }
}
