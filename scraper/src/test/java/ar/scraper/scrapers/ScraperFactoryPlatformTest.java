package ar.scraper.scrapers;

import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.BasePage;
import ar.scraper.pages.FullH4rdPage;
import ar.scraper.pages.InproPage;
import ar.scraper.pages.MonkyforcePage;
import ar.scraper.pages.MorashopPage;
import ar.scraper.pages.OsCommercePage;
import ar.scraper.pages.PrestashopPage;
import ar.scraper.pages.QloudPage;
import ar.scraper.pages.ShopifyPage;
import ar.scraper.pages.TechStorePage;
import ar.scraper.pages.TechStorePage.TechStoreType;
import ar.scraper.pages.TiendanubePage;
import ar.scraper.pages.VaypolPage;
import ar.scraper.pages.VtexPage;
import ar.scraper.pages.WooCommercePage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;

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
 *
 * <p>The routing is observed through the page object the scraper builds, not
 * the scraper's class: a page's constructor only stores fields, so
 * {@code mockConstruction} records which one {@code scrape} instantiated and
 * with which arguments, without touching a browser.
 */
@Epic("Scraping Engine")
@Feature("Platform Detection")
@DisplayName("ScraperFactory — platform routing by site name/URL")
class ScraperFactoryPlatformTest {

    private static final ScraperConfig CONFIG = new ScraperConfig();

    private static final List<Class<? extends BasePage>> PAGINAS = List.of(
            WooCommercePage.class, TechStorePage.class, FullH4rdPage.class, VaypolPage.class,
            QloudPage.class, OsCommercePage.class, InproPage.class, VtexPage.class, ShopifyPage.class,
            TiendanubePage.class, MonkyforcePage.class, MorashopPage.class, PrestashopPage.class);

    private static final SiteRegistry SITE_REGISTRY = SiteRegistry.forTesting(Map.of(
            "forever", new SiteRegistry.Sitio("Forever", "forever", "shopify", false, null, "config"),
            "freres", new SiteRegistry.Sitio("Freres", "freres", "shopify", false, null, "config"),
            "vcp", new SiteRegistry.Sitio("Vcp", "vcp", "shopify", false, null, "config"),
            "foreverbstrd", new SiteRegistry.Sitio("Foreverbstrd", "foreverbstrd", "tiendanube", false, null, "config"),
            "barnes", new SiteRegistry.Sitio("Barnes", "barnes", "tiendanube", false, null, "config"),
            "rockethard", new SiteRegistry.Sitio("Rockethard", "rockethard", "qloud", false, "tecnologia", "config"),
            "venex", new SiteRegistry.Sitio("Venex", "venex", "oscommerce", false, "tecnologia", "config"),
            "morashop", new SiteRegistry.Sitio("Morashop", "morashop", "morashop", false, "suplementos", "config")
    ));

    private record Construida(Class<? extends BasePage> pagina, List<?> args) {}

    @Step("Page built for sitio={nombre}, url={url}")
    private Construida paginaDe(String nombre, String url) {
        return paginaDe(SITE_REGISTRY, new SiteConfig(nombre, url, "moda"));
    }

    private static Construida paginaDe(SiteRegistry registry, SiteConfig site) {
        List<Construida> construidas = new ArrayList<>();
        List<MockedConstruction<?>> abiertas = new ArrayList<>();
        try {
            for (Class<? extends BasePage> pagina : PAGINAS) {
                abiertas.add(mockConstruction(pagina,
                        (mock, ctx) -> construidas.add(new Construida(pagina, ctx.arguments()))));
            }
            ScraperFactory.crear(CONFIG, site, registry).scrape(mock(Page.class));
        } finally {
            abiertas.forEach(MockedConstruction::close);
        }
        assertThat(construidas).as("exactly one page object per scrape").hasSize(1);
        return construidas.get(0);
    }

    @Test
    void foreverRoutesToShopify() {
        assertThat(paginaDe("forever", "https://forever.com.ar/collections/all").pagina())
                .isEqualTo(ShopifyPage.class);
    }

    @Test
    void existingShopifyNamesStillRouteToShopify() {
        assertThat(paginaDe("freres", "https://freres.ar/collections/all").pagina())
                .isEqualTo(ShopifyPage.class);
        assertThat(paginaDe("vcp", "https://vcp.com.ar").pagina())
                .isEqualTo(ShopifyPage.class);
    }

    @Test
    void myshopifyUrlRoutesToShopifyRegardlessOfName() {
        assertThat(paginaDe("cualquiera", "https://tienda.myshopify.com").pagina())
                .isEqualTo(ShopifyPage.class);
    }

    @Test
    void foreverbstrdStaysOnTiendanube() {
        assertThat(paginaDe("foreverbstrd", "https://foreverbstrd.com/collections/all").pagina())
                .isEqualTo(TiendanubePage.class);
    }

    @Test
    void barnesStaysOnTiendanube() {
        assertThat(paginaDe("barnes", "https://barnesindustries.com.ar").pagina())
                .isEqualTo(TiendanubePage.class);
    }

    /**
     * Pins the triple match that keeps morashop off the default branch: the
     * V28 seed's {@code plataforma}, the {@code "morashop"} key in
     * {@link ScraperFactory#crear}, and {@code PLATAFORMAS_VALIDAS}. Drift here
     * does not throw, it falls through to {@link TiendanubePage}.
     *
     * <p>For this site that fallback is the worst possible one. Morashop's
     * configured URL is the {@code /suplementos/} section index, which serves
     * ZERO products: the base page would scrape it happily and report an empty
     * catalogue. Same silent-0 class as {@code forever} before V24, except
     * nothing would even look wrong.
     *
     * <p>Gap found by the four-lens review of PR #151, which flagged the
     * identical hole for {@code inpro}. Same fixture, same omission.
     */
    @Test
    void morashopRoutesToItsOwnPageAndNeverFallsThroughToTiendanube() {
        assertThat(paginaDe("morashop", "https://www.morashop.ar/suplementos/").pagina())
                .as("caer al default seria 0 productos en silencio: /suplementos/ no lista nada")
                .isEqualTo(MorashopPage.class);
    }

    @Test
    void rockethardRoutesToQloud() {
        assertThat(paginaDe("rockethard", "https://rockethard.com.ar").pagina())
                .isEqualTo(QloudPage.class);
    }

    @Test
    void venexRoutesToOsCommerce() {
        assertThat(paginaDe("venex", "https://www.venex.com.ar").pagina())
                .isEqualTo(OsCommercePage.class);
    }

    static Stream<Arguments> cadaPlataforma() {
        return Stream.of(
                Arguments.of("woocommerce", WooCommercePage.class),
                Arguments.of("maximus",     TechStorePage.class),
                Arguments.of("fullh4rd",    FullH4rdPage.class),
                Arguments.of("compragamer", TechStorePage.class),
                Arguments.of("vaypol",      VaypolPage.class),
                Arguments.of("qloud",       QloudPage.class),
                Arguments.of("oscommerce",  OsCommercePage.class),
                Arguments.of("inpro",       InproPage.class),
                Arguments.of("vtex",        VtexPage.class),
                Arguments.of("shopify",     ShopifyPage.class),
                Arguments.of("monkyforce",  MonkyforcePage.class),
                Arguments.of("morashop",    MorashopPage.class),
                Arguments.of("prestashop",  PrestashopPage.class),
                Arguments.of("tiendanube",  TiendanubePage.class));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("cadaPlataforma")
    void everyPlatformBuildsItsPageWithTheSiteDisplayNameAndUrl(String plataforma, Class<?> esperada) {
        Construida c = paginaDe(registryCon(plataforma), siteTienda());

        assertThat(c.pagina()).isEqualTo(esperada);
        assertThat(c.args().get(1)).as("timeoutMs").isEqualTo(CONFIG.getTimeoutMs());
        assertThat(c.args().get(2)).as("sitio").isEqualTo("Tienda");
        assertThat(c.args().get(3)).as("baseUrl").isEqualTo("https://tienda.com.ar");
        assertThat(c.args().get(4)).as("precioMin").isEqualTo(CONFIG.getPrecioMinimo());
        assertThat(c.args().get(5)).as("precioMax").isEqualTo(CONFIG.getPrecioMaximo());
    }

    @Test
    void techStoreSitesKeepTheirOwnStoreType() {
        assertThat(paginaDe(registryCon("maximus"), siteTienda()).args())
                .last().isEqualTo(TechStoreType.MAXIMUS);
        assertThat(paginaDe(registryCon("compragamer"), siteTienda()).args())
                .last().isEqualTo(TechStoreType.COMPRAGAMER);
    }

    @Test
    void tiendanubeFamilyReceivesExtraUrlsAndPageCap() {
        List<String> extra = List.of("https://tienda.com.ar/ofertas");
        for (String plataforma : List.of("tiendanube", "monkyforce", "morashop")) {
            List<?> args = paginaDe(registryCon(plataforma),
                    new SiteConfig("tienda", "https://tienda.com.ar", "moda", extra)).args();
            assertThat(args.get(6)).as(plataforma + " extraUrls").isEqualTo(extra);
            assertThat(args.get(7)).as(plataforma + " maxPaginas")
                    .isEqualTo(CONFIG.getMaxPaginas("Tienda", TiendanubePage.MAX_PAGINAS_DEFAULT));
        }
    }

    @Test
    void vtexUrlsRouteToVtexRegardlessOfName() {
        assertThat(paginaDe("cualquiera", "https://x.vtexcommercestable.com.br").pagina())
                .isEqualTo(VtexPage.class);
        assertThat(paginaDe("cualquiera", "https://x.vteximg.com.br").pagina())
                .isEqualTo(VtexPage.class);
    }

    private static SiteRegistry registryCon(String plataforma) {
        return SiteRegistry.forTesting(Map.of(
                "tienda", new SiteRegistry.Sitio("Tienda", "tienda", plataforma, false, null, "config")));
    }

    private static SiteConfig siteTienda() {
        return new SiteConfig("tienda", "https://tienda.com.ar", "moda");
    }
}
