package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FullH4rd's listing markup serves the product image as a ROOT-RELATIVE path
 * ({@code src="/img/productos/3/{slug}-0.jpg"}, measured live 2026-08-15 on
 * {@code /cat/supra/3/placas-de-video/1}). {@code fromNode} only ever handled
 * the protocol-relative {@code //host/...} form, so every FullH4rd row was
 * written to {@code productos.imagen_url} as a bare path — unfetchable by the
 * dashboard and by the zero-shot visual classifier alike.
 *
 * <p>The tell is the asymmetry inside {@code fromNode}: the {@code href} two
 * lines above IS joined against the origin by the extractor, the {@code src}
 * never was.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("FullH4rd listing images are stored absolute, not as bare paths")
@DisplayName("TechStorePage.parseProductNodes — FullH4rd/generic node mapping")
class TechStorePageFullH4rdTest {

    private static final String BASE_URL = "https://fullh4rd.com.ar";

    private static TechStorePage fullH4rdPage() {
        Page mockPage = Mockito.mock(Page.class);
        return new TechStorePage(mockPage, 30000, "Fullh4rd", BASE_URL,
                0, 100_000_000, TechStorePage.TechStoreType.FULLH4RD);
    }

    @Test
    @DisplayName("el src root-relative del listado se guarda absoluto contra el origen")
    void absolutizesRootRelativeImageSrc() {
        String nodes = """
                [{"nombre":"Placa de Video Geforce GT 210 1GB DDR3 VGA HDMI DVI Bulk",
                  "precio":"$85.999",
                  "precioOrig":"",
                  "url":"https://fullh4rd.com.ar/prod/32044/placa-de-video-geforce-gt-210",
                  "img":"/img/productos/3/placa-de-video-geforce-gt-210-1gb-ddr3-vga-hdmi-dvi-bulk-0.jpg"}]""";

        List<Product> result = fullH4rdPage().parseProductNodes(nodes, new HashSet<>());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imagenUrl()).isEqualTo(
                "https://fullh4rd.com.ar/img/productos/3/placa-de-video-geforce-gt-210-1gb-ddr3-vga-hdmi-dvi-bulk-0.jpg");
    }

    @Test
    @DisplayName("el src protocol-relative sigue tomando https (no se rompe lo que ya andaba)")
    void stillAddsHttpsToProtocolRelativeSrc() {
        String nodes = """
                [{"nombre":"Memoria RAM Kingston Fury 16GB DDR5",
                  "precio":"$120.000","precioOrig":"","url":"https://fullh4rd.com.ar/prod/1/ram",
                  "img":"//cdn.fullh4rd.com.ar/img/ram.jpg"}]""";

        List<Product> result = fullH4rdPage().parseProductNodes(nodes, new HashSet<>());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imagenUrl()).isEqualTo("https://cdn.fullh4rd.com.ar/img/ram.jpg");
    }

    @Test
    @DisplayName("sin img -> imagen vacia (abstencion, CODE-5), no el origen pelado")
    void abstainsWhenTheCardHasNoImage() {
        String nodes = """
                [{"nombre":"Gabinete Generico ATX","precio":"$50.000","precioOrig":"",
                  "url":"https://fullh4rd.com.ar/prod/2/gabinete","img":""}]""";

        List<Product> result = fullH4rdPage().parseProductNodes(nodes, new HashSet<>());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).imagenUrl()).isEmpty();
    }
}
