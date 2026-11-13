package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDelSitio;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import ar.scraper.pages.CatalogPage;
import ar.scraper.promos.PaginaWeb;
import ar.scraper.promos.SitePromoSource;
import ar.scraper.scrape.CloudflareListener;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("BaseScraper — site promos are read once per run and never fail the product scrape")
class BaseScraperSitePromosTest {

    private static final Product PRODUCTO = Product.builder()
            .sitio("Vaypol").nombre("Zapatilla").precio(1000).url("https://vaypol.test/z").build();
    private static final PromoFinanciacion PROMO = new PromoFinanciacion("Visa", TipoMedio.TARJETA,
            TipoPromo.CUOTAS_SIN_INTERES, 6, null, Alcance.SITIO, null, null, "6 cuotas sin interés con Visa");
    private static final CatalogPage CATALOGO = () -> List.of(PRODUCTO);

    private static BaseScraper scraper(SitePromoSource fuente) {
        return new BaseScraper(new ScraperConfig(), "Vaypol", "https://vaypol.test", List.of(),
                (page, ctx) -> CATALOGO, CloudflareListener.NOOP, fuente);
    }

    @Test
    @DisplayName("the source's promos travel on the result, read from the site's base url")
    void promosViajanEnElResultado() {
        List<String> leidas = new ArrayList<>();
        SitePromoSource fuente = (base, web, hoy) -> {
            leidas.add(base);
            return PromosDelSitio.leidas(List.of(PROMO));
        };

        var resultado = scraper(fuente).resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.productos()).containsExactly(PRODUCTO);
        assertThat(resultado.promosDelSitio()).isEqualTo(PromosDelSitio.leidas(List.of(PROMO)));
        assertThat(leidas).containsExactly("https://vaypol.test");
    }

    @Test
    @DisplayName("a read of zero promos is still a successful read")
    void lecturaVaciaEsLeida() {
        var resultado = scraper((b, w, h) -> PromosDelSitio.leidas(List.of()))
                .resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.promosDelSitio().leido()).isTrue();
    }

    @Test
    @DisplayName("a source that throws leaves the products intact and the promos NO_LEIDO")
    void fuenteQueExplota() {
        var resultado = scraper((b, w, h) -> { throw new IllegalStateException("boom"); })
                .resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.productos()).containsExactly(PRODUCTO);
        assertThat(resultado.error()).isNull();
        assertThat(resultado.promosDelSitio()).isEqualTo(PromosDelSitio.NO_LEIDO);
    }

    @Test
    @DisplayName("exactly one source call per run, and the products-only entry point makes none")
    void unaVezPorCorrida() {
        int[] llamadas = {0};
        SitePromoSource fuente = (b, w, h) -> { llamadas[0]++; return PromosDelSitio.leidas(List.of()); };
        BaseScraper scraper = scraper(fuente);

        scraper.resultado(mock(Page.class), System.currentTimeMillis());
        assertThat(llamadas[0]).isEqualTo(1);

        scraper.scrape(mock(Page.class));
        assertThat(llamadas[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("the source gets a web that reads through the site's own page, today's date")
    void recibeUnaWebYLaFecha() {
        PaginaWeb[] recibida = new PaginaWeb[1];
        LocalDate[] fecha = new LocalDate[1];
        scraper((b, w, h) -> { recibida[0] = w; fecha[0] = h; return PromosDelSitio.NO_LEIDO; })
                .resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(recibida[0]).isInstanceOf(PlaywrightPaginaWeb.class);
        assertThat(fecha[0]).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("a truncated catalog still gets its promos read")
    void catalogoTruncado() {
        CatalogPage cortada = new CatalogPage() {
            public List<Product> scrapeAll() { return List.of(PRODUCTO); }
            public String truncamiento() { return "paginación cortada en p3: net::ERR_X"; }
        };
        var scraper = new BaseScraper(new ScraperConfig(), "Vaypol", "https://vaypol.test", List.of(),
                (page, ctx) -> cortada, CloudflareListener.NOOP, (b, w, h) -> PromosDelSitio.leidas(List.of(PROMO)));

        var resultado = scraper.resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.error()).contains("paginación cortada");
        assertThat(resultado.promosDelSitio().leido()).isTrue();
    }

    @Test
    @DisplayName("a catalog url is reduced to the store's home before the source reads it")
    void leeElOrigenYNoLaUrlDelCatalogo() {
        assertThat(BaseScraper.origen("https://www.batuk.com.ar/productos/")).isEqualTo("https://www.batuk.com.ar");
        assertThat(BaseScraper.origen("https://laspepas.com.ar/ar/ss27/ver-todo")).isEqualTo("https://laspepas.com.ar");
        assertThat(BaseScraper.origen("https://baesics.ar/")).isEqualTo("https://baesics.ar");
    }
}
