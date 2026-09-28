package ar.scraper.pages;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure static pagination helper
 * {@link TiendanubePage#resolveNextPageFromHrefs(List, int)}.
 *
 * The helper has no browser dependency — it only inspects href strings,
 * so every scenario can run without Playwright.
 */
@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("Pagination")
@DisplayName("TiendanubePage — next-page resolution from hrefs")
class TiendanubePagePaginationTest {

    // (a) hrefs=[/?page=1,/?page=2,/?page=3] @cur=1 → maxN=3, 3>1 → OptionalInt.of(4)
    @Test
    void multiplePagesAhead_returnsNextPage() {
        List<String> hrefs = List.of("/?page=1", "/?page=2", "/?page=3");
        Allure.parameter("hrefs", hrefs);
        Allure.parameter("currentPage", 1);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).hasValue(4);
    }

    // (b) hrefs=[/?page=1] @cur=1 → maxN=1, 1>1 is false → empty
    @Test
    void onlyCurrentPageInHrefs_returnsEmpty() {
        List<String> hrefs = List.of("/?page=1");
        Allure.parameter("hrefs", hrefs);
        Allure.parameter("currentPage", 1);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).isEmpty();
    }

    // (c) empty hrefs → maxN stays -1, -1>1 false → empty
    @Test
    void emptyHrefs_returnsEmpty() {
        Allure.parameter("hrefs", List.of());
        Allure.parameter("currentPage", 1);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(List.of(), 1);
        assertThat(result).isEmpty();
    }

    // (d) no page= pattern in hrefs → maxN stays -1 → empty
    @Test
    void noPagePattern_returnsEmpty() {
        List<String> hrefs = List.of("/?q=shirt", "/?category=shoes");
        Allure.parameter("hrefs", hrefs);
        Allure.parameter("currentPage", 1);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).isEmpty();
    }

    // (e) hrefs=[/?page=2,/?page=3] @cur=2 → maxN=3, 3>2 → OptionalInt.of(4)
    @Test
    void hrefsStartAboveCurrentPage_returnsNextPage() {
        List<String> hrefs = List.of("/?page=2", "/?page=3");
        Allure.parameter("hrefs", hrefs);
        Allure.parameter("currentPage", 2);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 2);
        assertThat(result).hasValue(4);
    }

    // ══════════════════════════════════════════════════════════════════
    // fix-failing-site-scrapers, T3 — el caso especial `mpage` se elimina:
    // existía sólo para Harvey /otras-temporadas1, y el sitio ignoraba el
    // parámetro server-side (misma página 18 productos siempre). Harvey pasa
    // a paginar por `?page=N`, que sí funciona ahí.
    // ══════════════════════════════════════════════════════════════════

    // Antes: el helper reconocía mpage= igual que page= (mpageHrefs_returnsNextPage,
    // ahora eliminado). CODE-2: comportamiento distinto, declarado — mpage= ya
    // no es un patrón de paginación válido.
    @Test
    void mpageHrefsYaNoSeReconocen() {
        List<String> hrefs = List.of("/otras-temporadas1?mpage=1", "/otras-temporadas1?mpage=2",
                "/otras-temporadas1?mpage=3");
        Allure.parameter("hrefs", hrefs);
        Allure.parameter("currentPage", 1);
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).isEmpty();
    }

    // ── urlPagina: preserva el param de paginación de la base ──────────

    @Test
    void urlPaginaUsaPagePorDefecto() {
        Allure.parameter("baseUrl", "https://x.com/productos/");
        Allure.parameter("targetPage", 2);
        assertThat(TiendanubePage.urlPagina("https://x.com/productos/", 2))
                .isEqualTo("https://x.com/productos/?page=2");
    }

    @Test
    void urlPaginaIncrementaPageExistente() {
        Allure.parameter("baseUrl", "https://x.com/productos/?page=2");
        Allure.parameter("targetPage", 3);
        assertThat(TiendanubePage.urlPagina("https://x.com/productos/?page=2", 3))
                .isEqualTo("https://x.com/productos/?page=3");
    }

    @Test
    void urlPaginaPaginaUnoDevuelveBase() {
        Allure.parameter("baseUrl", "https://x.com/otras-temporadas1/?page=1");
        Allure.parameter("targetPage", 1);
        assertThat(TiendanubePage.urlPagina("https://x.com/otras-temporadas1/?page=1", 1))
                .isEqualTo("https://x.com/otras-temporadas1/?page=1");
    }

    // ══════════════════════════════════════════════════════════════════
    // fix-failing-site-scrapers, T3 — guardia de repetición: un server que
    // ignora el parámetro de paginación (Harvey con `?mpage=N`, medido: los
    // mismos 18 productos en cada página) no puede costar 60 page loads.
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("una página con URLs nuevas no es repetida")
    void repiteLaAnterior_paginaNuevaNoEsRepetida() {
        Set<String> anterior = new LinkedHashSet<>();
        boolean repetida = TiendanubePage.repiteLaAnterior(
                List.of("/prod/1", "/prod/2"), anterior);
        assertThat(repetida).isFalse();
    }

    @Test
    @DisplayName("una página idéntica a la anterior es repetida")
    void repiteLaAnterior_paginaTotalmenteRepetidaEsRepetida() {
        Set<String> anterior = new LinkedHashSet<>(List.of("/prod/1", "/prod/2"));
        boolean repetida = TiendanubePage.repiteLaAnterior(
                List.of("/prod/1", "/prod/2"), anterior);
        assertThat(repetida)
                .as("el server ignoró el parámetro de paginación y sirvió la misma página")
                .isTrue();
    }

    @Test
    @DisplayName("una página que difiere de la anterior en una URL no es repetida")
    void repiteLaAnterior_paginaParcialmenteNuevaNoEsRepetida() {
        Set<String> anterior = new LinkedHashSet<>(List.of("/prod/1"));
        boolean repetida = TiendanubePage.repiteLaAnterior(
                List.of("/prod/1", "/prod/2"), anterior);
        assertThat(repetida).isFalse();
    }

    @Test
    @DisplayName("una página sin URLs no cuenta como repetida — es simplemente vacía")
    void repiteLaAnterior_paginaVaciaNoEsRepetida() {
        Set<String> anterior = new LinkedHashSet<>(List.of("/prod/1"));
        boolean repetida = TiendanubePage.repiteLaAnterior(List.of(), anterior);
        assertThat(repetida).isFalse();
    }

    @Test
    @DisplayName("scroll infinito: p1 ya cargó p2 en el DOM, así que ?page=2 no trae nada nuevo — pero NO es repetida")
    void scrollInfinitoNoEsRepetida() {
        // Medido en foreverbstrd y Harvey: el guard cortaba en p2 con 72 y 108 productos.
        Set<String> anterior = new LinkedHashSet<>(List.of("/prod/1", "/prod/2", "/prod/3", "/prod/4"));
        boolean repetida = TiendanubePage.repiteLaAnterior(List.of("/prod/3", "/prod/4"), anterior);
        assertThat(repetida).isFalse();
    }
}
