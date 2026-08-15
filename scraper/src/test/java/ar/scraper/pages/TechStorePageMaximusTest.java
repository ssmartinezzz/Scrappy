package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix-zero-yield-tech-sites, C6 (spec: {@code tech-site-catalog-coverage} /
 * Maximus, including the R1 loud-failure scenario — the highest-risk piece
 * of this whole change).
 *
 * <p>Confirmed live against maximus.com.ar (2026-08-13): a cookie-less POST
 * to {@code /wfmWebSite2.aspx/wsNRW_Script} returns HTTP 200 with
 * {@code {"d":"-2, Módulo GlobalBluePoint© GBPScripts NO ADQUIRIDO."}} — the
 * gate can't be detected from the status code, only from {@code d}'s shape.
 * A session-carrying call (cookies minted by a prior category-page GET)
 * returns the real paginated payload.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("Maximus reads its session-gated page-method API, never silently as empty")
@DisplayName("TechStorePage — Maximus URL scheme + session-gated API")
class TechStorePageMaximusTest {

    private static final String CAT48_PAGE1 = readFixture("cat48-page1.json");
    private static final String CAT48_PAGE2 = readFixture("cat48-page2.json");
    private static final String CAT48_PAGE3 = readFixture("cat48-page3.json");
    private static final String SESSION_GATE_D = readFixture("session-gate.json");

    // ─── parseMaximusPayload — pure ───────────────────────────────────────

    @Test
    @DisplayName("un payload de pagina exitosa parsea page/pagesTotal/itemsTotal/items")
    void parsesASuccessfulPagePayload() {
        var result = TechStorePage.parseMaximusPayload(CAT48_PAGE1);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pagesTotal()).isEqualTo(3);
        assertThat(result.itemsTotal()).isEqualTo(59);
        assertThat(result.items()).hasSize(20);
    }

    @Test
    @DisplayName("el session-gate ('-2, Modulo...') NUNCA se interpreta como categoria vacia — SIEMPRE lanza")
    void sessionGateThrowsInsteadOfReturningEmpty() {
        assertThatThrownBy(() -> TechStorePage.parseMaximusPayload(SESSION_GATE_D))
                .isInstanceOf(MaximusPayloadException.class)
                .hasMessageContaining("GBPScripts NO ADQUIRIDO");
    }

    @Test
    @DisplayName("un escalar JSON valido (no objeto) tambien lanza — no es una categoria vacia")
    void bareScalarThrows() {
        assertThatThrownBy(() -> TechStorePage.parseMaximusPayload("-2"))
                .isInstanceOf(MaximusPayloadException.class);
    }

    @Test
    @DisplayName("JSON invalido lanza (no NPE, no lista vacia silenciosa)")
    void unparseableJsonThrows() {
        assertThatThrownBy(() -> TechStorePage.parseMaximusPayload("{esto no es json"))
                .isInstanceOf(MaximusPayloadException.class);
    }

    @Test
    @DisplayName("d vacio o null lanza, nunca una categoria vacia silenciosa")
    void blankDThrows() {
        assertThatThrownBy(() -> TechStorePage.parseMaximusPayload(""))
                .isInstanceOf(MaximusPayloadException.class);
        assertThatThrownBy(() -> TechStorePage.parseMaximusPayload(null))
                .isInstanceOf(MaximusPayloadException.class);
    }

    // ─── crawlMaximusCategory — pagination loop, Mockito-mocked Page ──────

    @Test
    @DisplayName("crawl multi-pagina: 20+20+19=59 productos, 3 llamadas a la API, se detiene en pagesTotal")
    void crawlAccumulatesAcrossPagesAndStopsAtPagesTotal() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString(), any()))
                .thenReturn(wrapD(CAT48_PAGE1), wrapD(CAT48_PAGE2), wrapD(CAT48_PAGE3));

        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        List<Product> result = techStorePage.crawlMaximusCategory(48, new HashSet<>());

        assertThat(result).hasSize(59);
        verify(mockPage, times(3)).evaluate(anyString(), any());
        verify(mockPage, times(3)).navigate(anyString(), any());
    }

    @Test
    @DisplayName("regresion: la fixture ya no rinde 0 (Maximus reportaba 0 con el esquema de URL viejo)")
    void regressionYieldsMoreThanZero() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString(), any())).thenReturn(wrapD(CAT48_PAGE1));

        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        // pagesTotal=3 en la fixture, pero solo la pagina 1 está mockeada —
        // igual alcanza para probar que ya no rinde 0.
        List<Product> result = techStorePage.crawlMaximusCategory(48, new HashSet<>());
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("el session-gate en medio de un crawl se propaga como excepcion, NUNCA como lista vacia silenciosa")
    void crawlPropagatesTheSessionGateException() {
        Page mockPage = Mockito.mock(Page.class);
        // Página 1 exitosa, página 2 pierde la sesión (gate) — simula el caso real.
        when(mockPage.evaluate(anyString(), any()))
                .thenReturn(wrapD(CAT48_PAGE1), wrapSessionGate());

        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        Set<String> vistas = new HashSet<>();
        assertThatThrownBy(() -> techStorePage.crawlMaximusCategory(48, vistas))
                .isInstanceOf(MaximusPayloadException.class);
    }

    // ─── field mapping ─────────────────────────────────────────────────────

    @Test
    @DisplayName("precio = prli_price_original; precioOriginal = strikeThroughPrice_original SOLO si es realmente mayor")
    void mapsPrecioAndPrecioOriginalOnlyWhenRealMarkdown() {
        var page = TechStorePage.parseMaximusPayload(CAT48_PAGE1);

        Page mockPage = Mockito.mock(Page.class);
        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        when(mockPage.evaluate(anyString(), any())).thenReturn(wrapD(CAT48_PAGE1));
        List<Product> result = techStorePage.crawlMaximusCategory(48, new HashSet<>());

        Product conDescuentoReal = productoPorId(result, "19769");
        assertThat(conDescuentoReal.precio()).isEqualTo(433200.0);
        assertThat(conDescuentoReal.precioOriginal())
                .as("strikeThroughPrice_original (520000) > precio (433200) -> descuento real")
                .isEqualTo(520000.0);

        Product sinStrike = productoPorId(result, "16650");
        assertThat(sinStrike.precioOriginal())
                .as("item sin strikeThroughPrice_original -> null, nunca inventado")
                .isNull();

        Product strikeIgualAlPrecio = productoPorId(result, "19770");
        assertThat(strikeIgualAlPrecio.precioOriginal())
                .as("strikeThroughPrice_original == prli_price_original -> no es un descuento real -> null")
                .isNull();
    }

    @Test
    @DisplayName("url = baseUrl/Producto/{item_desc4link}/ITEM={item_id}/maximus.aspx")
    void mapsUrl() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString(), any())).thenReturn(wrapD(CAT48_PAGE1));
        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        Product p = productoPorId(techStorePage.crawlMaximusCategory(48, new HashSet<>()), "16650");
        assertThat(p.url()).isEqualTo(
                "https://www.maximus.com.ar/Producto/Placa-de-Video-Msi-Nvidia-Geforce-RTX-5070-Ventus-2X-12GB-OC-GDDR7/ITEM=16650/maximus.aspx");
    }

    @Test
    @DisplayName("imagen = /Temp/App_WebSite/App_PictureFiles/Items/{item_code4web}_600.jpg")
    void derivesImageUrlFromItemCode4Web() {
        // El item NO trae un campo de imagen, pero SI trae la clave con la que
        // el propio sitio la arma. Verificado en vivo (2026-08-15) con HEAD
        // sobre las 121 filas de las CAT 48/56/68/3/10: 121/121 en 200. El
        // comentario viejo del codigo ("ninguna key del item la trae") describia
        // mal el presente y por eso los 745 productos de Maximus se guardaban
        // sin imagen (DOC-3).
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString(), any())).thenReturn(wrapD(CAT48_PAGE1));
        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        Product p = productoPorId(techStorePage.crawlMaximusCategory(48, new HashSet<>()), "16650");
        assertThat(p.imagenUrl()).isEqualTo(
                "https://www.maximus.com.ar/Temp/App_WebSite/App_PictureFiles/Items/912-V532-009_600.jpg");
    }

    @Test
    @DisplayName("item_code4web ausente o vacio -> imagen vacia (abstencion, CODE-5), nunca una URL a medias")
    void abstainsOnImageWhenItemCodeIsMissing() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.evaluate(anyString(), any())).thenReturn(wrapD(SIN_ITEM_CODE));
        TechStorePage techStorePage = new TechStorePage(
                mockPage, 30000, "Maximus", "https://www.maximus.com.ar",
                0, 100_000_000, TechStorePage.TechStoreType.MAXIMUS);

        List<Product> result = techStorePage.crawlMaximusCategory(48, new HashSet<>());

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(p -> assertThat(p.imagenUrl()).isEmpty());
    }

    /** Dos items validos en todo lo demas: uno con {@code item_code4web} vacio, otro sin la key. */
    private static final String SIN_ITEM_CODE = """
            {"data":{"page":1,"pagesTotal":1,"itemsTotal":2,"items":[
              {"item_id":90001,"item_code4web":"","item_desc":"Gabinete Sin Codigo",
               "item_desc4link":"Gabinete-Sin-Codigo","prli_price_original":100000},
              {"item_id":90002,"item_desc":"Gabinete Sin La Key",
               "item_desc4link":"Gabinete-Sin-La-Key","prli_price_original":120000}
            ]}}""";

    // ─── category id discovery ──────────────────────────────────────────────

    @Test
    @DisplayName("descubre ids de categoria desde CAT= en el nav de la home")
    void extractsCategoryIdsFromNav() {
        String nav = "<a href=\"/Productos/Placas-De-Video/maximus.aspx?/CAT=48/SCAT=-1/M=-1/OR=3/PAGE=1/\">GPU</a>"
                + "<a href=\"/Productos/Notebooks/maximus.aspx?/CAT=56/SCAT=-1/M=-1/OR=1/PAGE=1/\">Notebooks</a>";

        List<Integer> ids = TechStorePage.extractMaximusCategoryIds(nav);

        assertThat(ids).containsExactlyInAnyOrder(48, 56);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Product productoPorId(List<Product> productos, String itemId) {
        return productos.stream()
                .filter(p -> p.url().contains("ITEM=" + itemId + "/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro item_id=" + itemId));
    }

    /** Wraps a bare `d`-value JSON object as the outer `{"d": "..."}` envelope the API actually returns. */
    private static String wrapD(String dValueJson) {
        return "{\"d\": " + toJsonStringLiteral(dValueJson) + "}";
    }

    private static String wrapSessionGate() {
        return "{\"d\": " + toJsonStringLiteral(SESSION_GATE_D) + "}";
    }

    private static String toJsonStringLiteral(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readFixture(String name) {
        String path = "/fixtures/maximus/" + name;
        try (InputStream in = TechStorePageMaximusTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
