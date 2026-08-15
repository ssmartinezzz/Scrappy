package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One owner for "turn whatever the markup put in {@code src} into something the
 * dashboard and the visual classifier can actually fetch" (CODE-6).
 *
 * <p>Before this class every reader re-implemented the join inline and each one
 * stopped at a different point: {@code QloudPage} and {@code TechStorePage}
 * handled only the protocol-relative {@code //host/...} form, so FullH4rd —
 * whose live markup serves {@code src="/img/productos/3/{slug}-0.jpg"} — stored
 * a root-relative path in {@code productos.imagen_url} for every row it ever
 * wrote. Measured live 2026-08-15.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("Listing image URLs are absolutized against the site base URL")
@DisplayName("ImageUrl.absolutize — listing image URL normalization")
class ImageUrlAbsolutizeTest {

    private static final String BASE = "https://fullh4rd.com.ar";

    @Test
    @DisplayName("root-relative (/img/...) se une contra el ORIGEN, no se deja crudo")
    void joinsRootRelativeAgainstTheOrigin() {
        assertThat(ImageUrl.absolutize("/img/productos/3/placa-de-video-rtx-5070-0.jpg", BASE))
                .isEqualTo("https://fullh4rd.com.ar/img/productos/3/placa-de-video-rtx-5070-0.jpg");
    }

    @Test
    @DisplayName("path-relative (products_images/...) se une contra el origen con una sola barra")
    void joinsPathRelativeAgainstTheOrigin() {
        assertThat(ImageUrl.absolutize("products_images/thumb/1727970464_1.jpg", "https://www.venex.com.ar/"))
                .isEqualTo("https://www.venex.com.ar/products_images/thumb/1727970464_1.jpg");
    }

    @Test
    @DisplayName("protocol-relative (//host/...) toma https")
    void addsHttpsToProtocolRelative() {
        assertThat(ImageUrl.absolutize("//cdn.qloud.ar/153/thumb_x.webp", BASE))
                .isEqualTo("https://cdn.qloud.ar/153/thumb_x.webp");
    }

    @Test
    @DisplayName("absoluta se devuelve intacta, en http y en https")
    void leavesAbsoluteUrlsUntouched() {
        assertThat(ImageUrl.absolutize("https://cdn.qloud.ar/153/a.webp", BASE))
                .isEqualTo("https://cdn.qloud.ar/153/a.webp");
        assertThat(ImageUrl.absolutize("http://cdn.qloud.ar/153/a.webp", BASE))
                .isEqualTo("http://cdn.qloud.ar/153/a.webp");
    }

    @Test
    @DisplayName("sin imagen -> cadena vacia (abstencion, CODE-5), nunca null ni una URL inventada")
    void abstainsWhenThereIsNoImage() {
        assertThat(ImageUrl.absolutize(null, BASE)).isEmpty();
        assertThat(ImageUrl.absolutize("", BASE)).isEmpty();
        assertThat(ImageUrl.absolutize("   ", BASE)).isEmpty();
    }

    @Test
    @DisplayName("relativa SIN baseUrl utilizable -> abstencion: un path relativo guardado es inservible")
    void abstainsOnRelativeWithoutABaseUrl() {
        assertThat(ImageUrl.absolutize("/img/a.jpg", null)).isEmpty();
        assertThat(ImageUrl.absolutize("/img/a.jpg", "")).isEmpty();
    }

    @Test
    @DisplayName("barras duplicadas en la junta se colapsan a una")
    void doesNotDoubleUpSlashesAtTheJoin() {
        assertThat(ImageUrl.absolutize("/img/a.jpg", "https://fullh4rd.com.ar///"))
                .isEqualTo("https://fullh4rd.com.ar/img/a.jpg");
    }
}
