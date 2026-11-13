package ar.scraper.promos;

import ar.scraper.model.promo.PromosDelSitio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static ar.scraper.promos.SitePromoFixtures.Web;
import static ar.scraper.promos.SitePromoFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BannerSitePromoSource — home banners + one linked payments page -> site promos")
class BannerSitePromoSourceTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 13);
    private final BannerSitePromoSource fuente = new BannerSitePromoSource();

    private static String pagina(String cuerpo) {
        return "<html><body><p>Tienda online de ropa, zapatillas y accesorios para toda la familia.</p>" + cuerpo
                + "</body></html>";
    }

    @Test
    @DisplayName("sporting (VTEX): pre-header banner, then the linked /medios-de-pagos page with named banks")
    void sporting() {
        Web web = new Web(Map.of(
                "https://www.sporting.com.ar", fixture("vtex-sporting-home.html"),
                "https://www.sporting.com.ar/medios-de-pagos", fixture("vtex-sporting-medios-de-pagos.html")));

        PromosDelSitio r = fuente.leer("https://www.sporting.com.ar", web, HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).contains(
                "Visa|CUOTAS_SIN_INTERES|12|null",
                "Mastercard|CUOTAS_SIN_INTERES|12|null",
                "Modo|DESCUENTO|null|30.00",
                "Modo|DESCUENTO|null|20.00",
                "Naranja X|DESCUENTO|null|10.00",
                "Naranja X|CUOTAS_SIN_INTERES|6|null",
                "Santander|CUOTAS_SIN_INTERES|12|null",
                "Santander|CUOTAS_SIN_INTERES|6|null",
                "Macro|CUOTAS_SIN_INTERES|12|null",
                "Galicia|CUOTAS_SIN_INTERES|6|null");
        // bare lines under a bank heading ("3 y 6 cuotas sin interés") name no medio: abstention, not "tarjeta"
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen)
                .doesNotContain("Tarjeta de crédito|CUOTAS_SIN_INTERES|12|null");
        assertThat(web.pedidos).containsExactly(
                "https://www.sporting.com.ar", "https://www.sporting.com.ar/medios-de-pagos");
    }

    @Test
    @DisplayName("dcshoes (WooCommerce): header banner and the /promociones page")
    void dcshoes() {
        Web web = new Web(Map.of(
                "https://dcshoes.com.ar", fixture("woo-dcshoes-home.html"),
                "https://dcshoes.com.ar/promociones", fixture("woo-dcshoes-promociones.html")));

        PromosDelSitio r = fuente.leer("https://dcshoes.com.ar", web, HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).contains(
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null",
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|12|null");
        assertThat(web.pedidos).containsExactly("https://dcshoes.com.ar", "https://dcshoes.com.ar/promociones");
    }

    @Test
    @DisplayName("a home with no payments link costs exactly one request")
    void sinEnlaceDePagos() {
        Web web = new Web(Map.of("https://x.test", pagina("<div class=\"announcement\">6 cuotas sin interés con Galicia</div>")));

        PromosDelSitio r = fuente.leer("https://x.test", web, HOY);

        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).containsExactly("Galicia|CUOTAS_SIN_INTERES|6|null");
        assertThat(web.pedidos).containsExactly("https://x.test");
    }

    @Test
    @DisplayName("follows at most two same-host payments links; ignores other hosts")
    void maximoDosEnlaces() {
        String home = pagina("<a href=\"/medios-de-pago\">Medios de pago</a><a href=\"/formas-de-pago\">Formas de pago</a>"
                + "<a href=\"/promociones\">Promociones</a><a href=\"https://otro.test/medios-de-pago\">Medios de pago</a>");
        Web web = new Web(Map.of("https://x.test", home,
                "https://x.test/medios-de-pago", pagina("<p>12 cuotas sin interés con Santander</p>"),
                "https://x.test/formas-de-pago", pagina("<p>10% de descuento con Modo</p>"),
                "https://x.test/promociones", pagina("<p>nunca se pide</p>")));

        PromosDelSitio r = fuente.leer("https://x.test", web, HOY);

        assertThat(web.pedidos).containsExactly("https://x.test", "https://x.test/medios-de-pago", "https://x.test/formas-de-pago");
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen)
                .containsExactlyInAnyOrder("Santander|CUOTAS_SIN_INTERES|12|null", "Modo|DESCUENTO|null|10.00");
    }

    @Test
    @DisplayName("bank promos published only as an image: the alt text is read, a text-less image is abstention")
    void imagenesDeBanner() {
        Web web = new Web(Map.of("https://x.test", pagina(
                "<img src=\"a.jpg\" alt=\"25% de descuento con Banco Galicia\"><img src=\"b.jpg\" alt=\"\">"
                        + "<img src=\"c.jpg\" title=\"3 cuotas sin interés con Santander\"><img src=\"d.jpg\">")));

        PromosDelSitio r = fuente.leer("https://x.test", web, HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).containsExactlyInAnyOrder(
                "Galicia|DESCUENTO|null|25.00", "Santander|CUOTAS_SIN_INTERES|3|null");
    }

    @Test
    @DisplayName("promo-looking text inside product cards is not a store-wide promo")
    void textoDeTarjetasDeProducto() {
        Web web = new Web(Map.of("https://x.test", pagina(
                "<div class=\"product-card\">Zapatilla running 3 cuotas sin interés de $20.000</div>")));

        assertThat(fuente.leer("https://x.test", web, HOY)).isEqualTo(PromosDelSitio.leidas(List.of()));
    }

    @Test
    @DisplayName("a home that publishes nothing is a successful read of zero promos")
    void sinPromos() {
        assertThat(fuente.leer("https://x.test", new Web(Map.of("https://x.test", pagina(""))), HOY))
                .isEqualTo(PromosDelSitio.leidas(List.of()));
    }

    @Test
    @DisplayName("any failed fetch is NO_LEIDO: the home, or the payments page after a good home")
    void fallos() {
        assertThat(fuente.leer("https://x.test", url -> { throw new LecturaFallidaException("HTTP 429"); }, HOY))
                .isEqualTo(PromosDelSitio.NO_LEIDO);
        Web pagosCaidos = new Web(Map.of("https://x.test",
                pagina("<a href=\"/medios-de-pago\">Medios de pago</a><p>3 cuotas sin interés con Galicia</p>")));
        assertThat(fuente.leer("https://x.test", pagosCaidos, HOY)).isEqualTo(PromosDelSitio.NO_LEIDO);
    }

    @Test
    @DisplayName("a Cloudflare challenge, a captcha or an empty body is NO_LEIDO")
    void paginasDeBloqueo() {
        String cloudflare = "<html><head><title>Attention Required! | Cloudflare</title></head><body>"
                + "<p>Sorry, you have been blocked. You are unable to access this site.</p></body></html>";
        String captcha = "<html><body><div class=\"cf-turnstile\"></div><p>Verificá que sos humano para continuar navegando</p></body></html>";
        for (String html : new String[] {cloudflare, captcha, "", "<html><body></body></html>"}) {
            assertThat(fuente.leer("https://x.test", new Web(Map.of("https://x.test", html)), HOY))
                    .as(html).isEqualTo(PromosDelSitio.NO_LEIDO);
        }
    }
}
