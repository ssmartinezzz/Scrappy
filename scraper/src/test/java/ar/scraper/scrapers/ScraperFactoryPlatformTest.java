package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 */
@Epic("Scraping Engine")
@Feature("Platform Detection")
@DisplayName("ScraperFactory — platform routing by site name/URL")
class ScraperFactoryPlatformTest {

    private static final ScraperConfig CONFIG = new ScraperConfig();

    @Step("Create scraper for sitio={nombre}, url={url}")
    private BaseScraper crear(String nombre, String url) {
        return ScraperFactory.crear(CONFIG, new SiteConfig(nombre, url, "moda"));
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
    void foreverFavoritoResolvesToShopify() {
        assertThat(ScraperFactory.plataformaDeFavorito(
                "forever", "https://forever.com.ar/products/remera-basica"))
                .isEqualTo(ScraperFactory.FavPlatform.SHOPIFY);
    }
}
