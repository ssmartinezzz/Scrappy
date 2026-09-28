package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-failing-site-scrapers, T5 — fullh4rd.com.ar was redesigned. Verified
 * live 2026-09-28: {@code /productos?page=N} lists the WHOLE catalog (1918
 * products, 12/page, page 160 has 10 cards, page 161 has 0) as
 * {@code article.results-card}. Replaces {@code TechStorePage}'s
 * {@code FULLH4RD} branch, which read the OLD {@code /cat/supra/{id}/...}
 * markup ({@code .item-prod}) that no longer matches the live site.
 *
 * <p>{@link FullH4rdPage#parseListing} is pure static — no Playwright, no
 * network — tested here against an HTML fixture built from the real card
 * markup, the same pattern {@code QloudPage.parseListing} uses.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("FullH4rd — results-card listing parsing")
@DisplayName("FullH4rdPage.parseListing — results-card markup")
class FullH4rdPageTest {

    private static final String BASE_URL = "https://fullh4rd.com.ar";

    /** One real card (brief sample) + a second without price-list + a duplicate of the first URL. */
    private static String fixtureHtml() {
        return """
                <div class="results-grid">
                <article class="results-card">
                  <a href="/prod/32579/monitor-25-msi-pro-mp251" class="results-card__image-link">
                    <img src="/img/productos/18/monitor-25-msi-pro-mp251-0.jpg" alt="" class="results-card__image" loading="lazy">
                  </a>
                  <div class="results-card__content">
                    <p class="results-card__meta">
                      MONITORES LED
                    </p>
                    <h2 class="results-card__title">
                      <a href="/prod/32579/monitor-25-msi-pro-mp251" class="results-card__title-link">
                        MONITOR 25" MSI PRO MP251 E14L IPS 144HZ 1MS FHD VGA HDMI FICHA USA GAMER +Q 24
                      </a>
                    </h2>
                    <div class="results-card__price">
                      <p class="results-card__price-current">
                        $182.899,95
                      </p>
                      <p class="results-card__price-list">
                        $201.189,91
                      </p>
                    </div>
                  </div>
                  <div class="results-card__actions">acciones</div>
                </article>
                <article class="results-card">
                  <a href="/prod/9001/mouse-generico" class="results-card__image-link">
                    <img src="/img/productos/54/mouse-generico-0.jpg" alt="" class="results-card__image" loading="lazy">
                  </a>
                  <div class="results-card__content">
                    <p class="results-card__meta">
                      MOUSES
                    </p>
                    <h2 class="results-card__title">
                      <a href="/prod/9001/mouse-generico" class="results-card__title-link">
                        MOUSE GENERICO USB
                      </a>
                    </h2>
                    <div class="results-card__price">
                      <p class="results-card__price-current">
                        $9.999,00
                      </p>
                    </div>
                  </div>
                  <div class="results-card__actions">acciones</div>
                </article>
                <article class="results-card">
                  <a href="/prod/32579/monitor-25-msi-pro-mp251" class="results-card__image-link">
                    <img src="/img/productos/18/monitor-25-msi-pro-mp251-0.jpg" alt="" class="results-card__image" loading="lazy">
                  </a>
                  <div class="results-card__content">
                    <p class="results-card__meta">
                      MONITORES LED
                    </p>
                    <h2 class="results-card__title">
                      <a href="/prod/32579/monitor-25-msi-pro-mp251" class="results-card__title-link">
                        MONITOR 25" MSI PRO MP251 E14L IPS 144HZ 1MS FHD VGA HDMI FICHA USA GAMER +Q 24
                      </a>
                    </h2>
                    <div class="results-card__price">
                      <p class="results-card__price-current">
                        $182.899,95
                      </p>
                      <p class="results-card__price-list">
                        $201.189,91
                      </p>
                    </div>
                  </div>
                  <div class="results-card__actions">acciones</div>
                </article>
                </div>
                """;
    }

    private static List<Product> parse() {
        return FullH4rdPage.parseListing(fixtureHtml(), "Fullh4rd", BASE_URL, 0, 100_000_000);
    }

    @Test
    @DisplayName("dedup por URL: la tercera card (duplicada) no cuenta dos veces")
    void dedupsByUrl() {
        assertThat(parse()).hasSize(2);
    }

    @Test
    @DisplayName("price-current es el precio, price-list el precio original (tachado)")
    void mapsCurrentAndListPrices() {
        Product monitor = parse().stream()
                .filter(p -> p.url().endsWith("/prod/32579/monitor-25-msi-pro-mp251"))
                .findFirst().orElseThrow();

        assertThat(monitor.precio()).isEqualTo(182899.95);
        assertThat(monitor.precioOriginal()).isEqualTo(201189.91);
    }

    @Test
    @DisplayName("sin price-list, precioOriginal queda null — no una abstención rota")
    void noPriceListMeansNoOriginalPrice() {
        Product mouse = parse().stream()
                .filter(p -> p.url().endsWith("/prod/9001/mouse-generico"))
                .findFirst().orElseThrow();

        assertThat(mouse.precio()).isEqualTo(9999.00);
        assertThat(mouse.precioOriginal()).isNull();
    }

    @Test
    @DisplayName("page.content() re-serializa el DOM: las entidades del nombre y la categoría se decodifican")
    void decodesEntitiesFromSerializedDom() {
        String html = """
                <article class="results-card">
                  <div class="results-card__content">
                    <p class="results-card__meta">CABLES &amp; ADAPTADORES</p>
                    <h2 class="results-card__title">
                      <a href="/prod/1/cable" class="results-card__title-link">CABLE HDMI &amp; DP &lt;2M&gt;&nbsp;NEGRO</a>
                    </h2>
                    <div class="results-card__price"><p class="results-card__price-current">$9.999,00</p></div>
                  </div>
                </article>
                """;

        Product cable = FullH4rdPage.parseListing(html, "Fullh4rd", BASE_URL, 0, 100_000_000).get(0);

        assertThat(cable.nombre()).isEqualTo("CABLE HDMI & DP <2M> NEGRO");
        assertThat(cable.categoria()).isEqualTo("CABLES & ADAPTADORES");
    }

    @Test
    @DisplayName("el nombre viene del title-link, con espacios/saltos de línea recortados")
    void extractsTrimmedName() {
        Product monitor = parse().stream()
                .filter(p -> p.url().endsWith("/prod/32579/monitor-25-msi-pro-mp251"))
                .findFirst().orElseThrow();

        assertThat(monitor.nombre())
                .isEqualTo("MONITOR 25\" MSI PRO MP251 E14L IPS 144HZ 1MS FHD VGA HDMI FICHA USA GAMER +Q 24");
    }

    @Test
    @DisplayName("la URL root-relative se absolutiza contra el origen")
    void absolutizesProductUrl() {
        Product mouse = parse().stream()
                .filter(p -> p.nombre().equals("MOUSE GENERICO USB"))
                .findFirst().orElseThrow();

        assertThat(mouse.url()).isEqualTo("https://fullh4rd.com.ar/prod/9001/mouse-generico");
    }

    @Test
    @DisplayName("la imagen root-relative se absolutiza contra el origen, no queda un path pelado")
    void absolutizesImageUrl() {
        Product mouse = parse().stream()
                .filter(p -> p.nombre().equals("MOUSE GENERICO USB"))
                .findFirst().orElseThrow();

        assertThat(mouse.imagenUrl())
                .isEqualTo("https://fullh4rd.com.ar/img/productos/54/mouse-generico-0.jpg");
    }

    @Test
    @DisplayName("meta es la categoría")
    void metaIsCategory() {
        Product mouse = parse().stream()
                .filter(p -> p.nombre().equals("MOUSE GENERICO USB"))
                .findFirst().orElseThrow();

        assertThat(mouse.categoria()).isEqualTo("MOUSES");
    }

    @Test
    @DisplayName("HTML vacío o sin cards no rompe, devuelve vacío")
    void blankHtmlReturnsEmpty() {
        assertThat(FullH4rdPage.parseListing("", "Fullh4rd", BASE_URL, 0, 100_000_000)).isEmpty();
        assertThat(FullH4rdPage.parseListing("<div>nada acá</div>", "Fullh4rd", BASE_URL, 0, 100_000_000))
                .isEmpty();
    }

    @Test
    @DisplayName("lee el total que declara el listado")
    void totalDisponibles() {
        String html = "<p class=\"tw:m-0\">\n   1918 productos disponibles   </p>";
        assertThat(FullH4rdPage.totalDisponibles(html)).hasValue(1918);
        assertThat(FullH4rdPage.totalDisponibles("<p>sin contador</p>")).isEmpty();
    }

    @Test
    @DisplayName("una página vacía antes del total declarado no es el fin: es un bloqueo o un error del sitio")
    void paginaVaciaAntesDelTotalNoEsFin() {
        // Medido: el crawl cortó en 623 de 1918 con una página sin cards a mitad del catálogo.
        assertThat(FullH4rdPage.esFinDeCatalogo(0, 53, OptionalInt.of(1918))).isFalse();
        assertThat(FullH4rdPage.esFinDeCatalogo(0, 161, OptionalInt.of(1918))).isTrue();
        assertThat(FullH4rdPage.esFinDeCatalogo(0, 53, OptionalInt.empty())).isTrue();
        assertThat(FullH4rdPage.esFinDeCatalogo(12, 53, OptionalInt.of(1918))).isFalse();
    }
}
