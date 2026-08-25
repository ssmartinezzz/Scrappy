package ar.scraper.scrapers;

import ar.scraper.aggregator.normalize.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScraperFactory#crear} platform routing by site name.
 *
 * Regression context (2026-07-14): "forever" is a Shopify store
 * (forever.com.ar/products.json responds 200) but was missing from the
 * SHOPIFY name-set, so it fell through to the Tiendanube default and
 * scraped 0 products. "foreverbstrd" and "barnes" look similar by URL but
 * ARE Tiendanube stores (platform signature verified in their HTML) and
 * must keep routing to the default.
 *
 * <p>close-1nf-and-3nf-foundation extension (design E1): routing reads
 * {@code plataforma} from {@link SiteRegistry} now, not a name-set — the
 * registry below mirrors the same five sites' real {@code V18} seed values
 * (classpath-only, no DB), so this test still exercises the same scenarios.
 * "cualquiera" is deliberately unseeded: it must still route to Shopify
 * purely off the {@code myshopify.com} URL fallback, which stays in code.
 */
@Epic("Scraping Engine")
@Feature("Platform Detection")
@DisplayName("ScraperFactory — platform routing by site name/URL")
class ScraperFactoryPlatformTest {

    private static final ScraperConfig CONFIG = new ScraperConfig();

    private static final SiteRegistry SITE_REGISTRY = SiteRegistry.forTesting(Map.of(
            "forever", new SiteRegistry.Sitio("Forever", "forever", "shopify", false, null, "config"),
            "freres", new SiteRegistry.Sitio("Freres", "freres", "shopify", false, null, "config"),
            "vcp", new SiteRegistry.Sitio("Vcp", "vcp", "shopify", false, null, "config"),
            "foreverbstrd", new SiteRegistry.Sitio("Foreverbstrd", "foreverbstrd", "tiendanube", false, null, "config"),
            "barnes", new SiteRegistry.Sitio("Barnes", "barnes", "tiendanube", false, null, "config"),
            "rockethard", new SiteRegistry.Sitio("Rockethard", "rockethard", "qloud", false, "tecnologia", "config"),
            "venex", new SiteRegistry.Sitio("Venex", "venex", "oscommerce", false, "tecnologia", "config")
    ));

    @Step("Create scraper for sitio={nombre}, url={url}")
    private BaseScraper crear(String nombre, String url) {
        return ScraperFactory.crear(CONFIG, new SiteConfig(nombre, url, "moda"), SITE_REGISTRY);
    }

    @Test
    void foreverRoutesToShopify() {
        assertThat(crear("forever", "https://forever.com.ar/collections/all"))
                .isInstanceOf(ShopifyScraper.class);
    }

    @Test
    void existingShopifyNamesStillRouteToShopify() {
        assertThat(crear("freres", "https://freres.ar/collections/all"))
                .isInstanceOf(ShopifyScraper.class);
        assertThat(crear("vcp", "https://vcp.com.ar"))
                .isInstanceOf(ShopifyScraper.class);
    }

    @Test
    void myshopifyUrlRoutesToShopifyRegardlessOfName() {
        assertThat(crear("cualquiera", "https://tienda.myshopify.com"))
                .isInstanceOf(ShopifyScraper.class);
    }

    @Test
    void foreverbstrdStaysOnTiendanube() {
        assertThat(crear("foreverbstrd", "https://foreverbstrd.com/collections/all"))
                .isInstanceOf(TiendanubeScraper.class);
    }

    @Test
    void barnesStaysOnTiendanube() {
        assertThat(crear("barnes", "https://barnesindustries.com.ar"))
                .isInstanceOf(TiendanubeScraper.class);
    }

    @Test
    void rockethardRoutesToQloud() {
        assertThat(crear("rockethard", "https://rockethard.com.ar"))
                .isInstanceOf(QloudScraper.class);
    }

    @Test
    void venexRoutesToOsCommerce() {
        assertThat(crear("venex", "https://www.venex.com.ar"))
                .isInstanceOf(OsCommerceScraper.class);
    }
}
