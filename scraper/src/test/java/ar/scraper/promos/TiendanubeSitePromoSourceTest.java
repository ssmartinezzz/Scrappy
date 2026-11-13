package ar.scraper.promos;

import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromosDelSitio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static ar.scraper.promos.SitePromoFixtures.Web;
import static ar.scraper.promos.SitePromoFixtures.fixture;
import static ar.scraper.promos.SitePromoFixtures.resumen;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TiendanubeSitePromoSource — one storefront home read -> store-wide promos")
class TiendanubeSitePromoSourceTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 13);
    private final TiendanubeSitePromoSource fuente = new TiendanubeSitePromoSource();

    private static final String PAGINA_SIN_PROMOS = "<html><body><h1>Tienda de prueba</h1>"
            + "<p>Bienvenidos a nuestra tienda online, mirá los productos nuevos de la temporada.</p></body></html>";

    @Test
    @DisplayName("batuk: interest-free cuotas per gateway, transfer discount proven from prices, banner dedup")
    void batuk() {
        Web web = new Web(Map.of("https://batuk.com.ar", fixture("tiendanube-batuk-home.html")));

        PromosDelSitio r = fuente.leer("https://batuk.com.ar", web, HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).containsExactlyInAnyOrder(
                "Pago Nube|CUOTAS_SIN_INTERES|3|null",
                "Mercado Pago|CUOTAS_SIN_INTERES|3|null",
                "Transferencia|DESCUENTO|null|10.00",
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null");
        assertThat(r.promos()).allSatisfy(p -> assertThat(p.alcance()).isEqualTo(Alcance.SITIO));
        assertThat(web.pedidos).containsExactly("https://batuk.com.ar");
    }

    @Test
    @DisplayName("mmartinez: gateways only offer 1 cuota (no promo), the banner's cuotas and the 15% transfer survive")
    void mmartinez() {
        Web web = new Web(Map.of("https://mmartinez.com.ar", fixture("tiendanube-mmartinez-home.html")));

        PromosDelSitio r = fuente.leer("https://mmartinez.com.ar", web, HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen).containsExactlyInAnyOrder(
                "Transferencia|DESCUENTO|null|15.00",
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|3|null",
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null",
                "Tarjeta de crédito|CUOTAS_SIN_INTERES|9|null");
    }

    @Test
    @DisplayName("a home that publishes nothing is a successful read of zero promos")
    void sinPromos() {
        PromosDelSitio r = fuente.leer("https://x.test", new Web(Map.of("https://x.test", PAGINA_SIN_PROMOS)), HOY);

        assertThat(r).isEqualTo(PromosDelSitio.leidas(java.util.List.of()));
    }

    @Test
    @DisplayName("an HTTP failure is NO_LEIDO, never an empty read")
    void falloDeRed() {
        PromosDelSitio r = fuente.leer("https://x.test", url -> { throw new LecturaFallidaException("HTTP 429"); }, HOY);

        assertThat(r).isEqualTo(PromosDelSitio.NO_LEIDO);
        assertThat(fuente.leer("https://x.test", url -> { throw new IllegalStateException("boom"); }, HOY))
                .isEqualTo(PromosDelSitio.NO_LEIDO);
    }

    @Test
    @DisplayName("a Cloudflare challenge, a captcha or an empty body is NO_LEIDO")
    void paginasDeBloqueo() {
        String cloudflare = "<html><head><title>Just a moment...</title></head><body>"
                + "<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\"></script>Checking your browser before accessing</body></html>";
        String captcha = "<html><body><div class=\"g-recaptcha\"></div><p>Verificá que sos humano para continuar navegando en este sitio</p></body></html>";
        for (String html : new String[] {cloudflare, captcha, "", "   ", "<html><body></body></html>"}) {
            assertThat(fuente.leer("https://x.test", new Web(Map.of("https://x.test", html)), HOY))
                    .as(html).isEqualTo(PromosDelSitio.NO_LEIDO);
        }
    }

    @Test
    @DisplayName("an inconsistent transfer discount across products is not asserted")
    void descuentoInconsistente() {
        String a = fixture("tiendanube-batuk-home.html");
        String distorsionada = a.replace("price_with_payment_discount_short&quot;:&quot;$62.991,00&quot;",
                "price_with_payment_discount_short&quot;:&quot;$50.000,00&quot;");
        PromosDelSitio r = fuente.leer("https://b.test", new Web(Map.of("https://b.test", distorsionada)), HOY);

        assertThat(r.leido()).isTrue();
        assertThat(r.promos()).extracting(SitePromoFixtures::resumen)
                .contains("Transferencia|DESCUENTO|null|10.00"); // still proven by the banner text
        assertThat(resumen(r.promos().get(0))).doesNotContain("50.00");
    }
}
