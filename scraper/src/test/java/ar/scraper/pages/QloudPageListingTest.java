package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-zero-yield-tech-sites, C4 (spec: {@code tech-site-catalog-coverage} /
 * Rockethard Scraped Through a Qloud Reader, design D5/D6).
 *
 * <p>Rockethard is server-rendered — no JS hydration needed, {@code curl}
 * already sees the product cards. Qloud's card markup renders every product
 * TWICE (a desktop card and a responsive mobile duplicate, identical URL
 * both times), so {@code parseListing} must dedup within a single page, not
 * just across pages.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("Rockethard reads Qloud's server-rendered category listing")
@DisplayName("QloudPage.parseListing — Rockethard/Qloud category listing")
class QloudPageListingTest {

    private static final String BASE_URL = "https://rockethard.com.ar";
    private static final double MIN = 1000;
    private static final double MAX = 1_000_000;

    private static final String HARDWARE_HTML = readFixture("hardware.html");
    private static final String NAV_HTML = readFixture("nav.html");

    @Test
    @DisplayName("dedup desktop/mobile: 5 card blocks, 4 URLs unicas, 3 dentro de banda de precio")
    void parsesUniqueProductsWithinPriceBand() {
        List<Product> result = QloudPage.parseListing(
                HARDWARE_HTML, "Rockethard", BASE_URL, "Hardware", MIN, MAX);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(Product::url))
                .containsExactlyInAnyOrder(
                        "https://rockethard.com.ar/hardware/fuente/fuente-sfx-500w-20+4-sata-163939.html",
                        "https://rockethard.com.ar/hardware/fuente/fuente-jalatec-jt-520-163961.html",
                        "https://rockethard.com.ar/hardware/memoria-ddr4/memoria-4gb-ddr4-2666-adata-160935.html");
    }

    @Test
    @DisplayName("regresion: la fixture ya no rinde 0 (Rockethard nunca estuvo registrado)")
    void regressionYieldsMoreThanZero() {
        List<Product> result = QloudPage.parseListing(
                HARDWARE_HTML, "Rockethard", BASE_URL, "Hardware", MIN, MAX);
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("precio = data-precio; precioOriginal = tachado cuando es mayor, null si no hay markdown real")
    void mapsPrecioAndPrecioOriginal() {
        List<Product> result = QloudPage.parseListing(
                HARDWARE_HTML, "Rockethard", BASE_URL, "Hardware", MIN, MAX);

        Product sfx = productoPorUrl(result, "fuente-sfx-500w");
        assertThat(sfx.precio()).isEqualTo(20142.0);
        assertThat(sfx.precioOriginal()).isEqualTo(23766.0);

        Product memoria = productoPorUrl(result, "memoria-4gb-ddr4");
        assertThat(memoria.precio()).isEqualTo(42265.0);
        assertThat(memoria.precioOriginal()).isNull();
    }

    @Test
    @DisplayName("nombre e imagen se mapean desde el card")
    void mapsNombreEImagen() {
        Product sfx = productoPorUrl(
                QloudPage.parseListing(HARDWARE_HTML, "Rockethard", BASE_URL, "Hardware", MIN, MAX),
                "fuente-sfx-500w");
        assertThat(sfx.nombre()).isEqualTo("Fuente Sfx 500w 20+4 Sata");
        assertThat(sfx.imagenUrl())
                .isEqualTo("https://app.contabilium.com/files/explorer/48428/Productos-Servicios/concepto-21666828.png");
    }

    @Test
    @DisplayName("categoria = el hint crudo pasado por el caller (raw hint, CategoryClassifier decide despues)")
    void categoriaIsTheRawHint() {
        Product sfx = productoPorUrl(
                QloudPage.parseListing(HARDWARE_HTML, "Rockethard", BASE_URL, "Hardware", MIN, MAX),
                "fuente-sfx-500w");
        assertThat(sfx.categoria()).isEqualTo("Hardware");
    }

    @Test
    @DisplayName("/productos nunca aparece en el crawl-plan de slugs de categoria")
    void neverIncludesProductosInCategoryCrawlPlan() {
        List<String> descubiertos = QloudPage.extractCategorySlugs(NAV_HTML, BASE_URL);

        assertThat(descubiertos).isNotEmpty();
        assertThat(descubiertos).doesNotContain("productos");
        assertThat(QloudPage.FALLBACK_SLUGS).doesNotContain("productos");
    }

    @Test
    @DisplayName("discovery real: incluye categorias del nav mas alla del fallback fijo (butacas, etc)")
    void discoveryIncludesRealCategoriesBeyondFallback() {
        List<String> descubiertos = QloudPage.extractCategorySlugs(NAV_HTML, BASE_URL);

        assertThat(descubiertos).contains("hardware", "perifericos", "monitores", "gabinete",
                "refrigeracion", "conectividad", "almacenamiento", "accesorios-", "butacas");
        assertThat(descubiertos).doesNotContain("contacto", "atencion-a-empresas", "arma-tu-pc");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Product productoPorUrl(List<Product> productos, String urlFragment) {
        return productos.stream()
                .filter(p -> p.url().contains(urlFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro producto con url conteniendo " + urlFragment));
    }

    private static String readFixture(String name) {
        String path = "/fixtures/rockethard/" + name;
        try (InputStream in = QloudPageListingTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
