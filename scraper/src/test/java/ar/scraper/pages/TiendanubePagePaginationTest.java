package ar.scraper.pages;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure static pagination helper
 * {@link TiendanubePage#resolveNextPageFromHrefs(List, int)}.
 *
 * The helper has no browser dependency — it only inspects href strings,
 * so every scenario can run without Playwright.
 */
class TiendanubePagePaginationTest {

    // (a) hrefs=[/?page=1,/?page=2,/?page=3] @cur=1 → maxN=3, 3>1 → OptionalInt.of(4)
    @Test
    void multiplePagesAhead_returnsNextPage() {
        List<String> hrefs = List.of("/?page=1", "/?page=2", "/?page=3");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).hasValue(4);
    }

    // (b) hrefs=[/?page=1] @cur=1 → maxN=1, 1>1 is false → empty
    @Test
    void onlyCurrentPageInHrefs_returnsEmpty() {
        List<String> hrefs = List.of("/?page=1");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).isEmpty();
    }

    // (c) empty hrefs → maxN stays -1, -1>1 false → empty
    @Test
    void emptyHrefs_returnsEmpty() {
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(List.of(), 1);
        assertThat(result).isEmpty();
    }

    // (d) no page= pattern in hrefs → maxN stays -1 → empty
    @Test
    void noPagePattern_returnsEmpty() {
        List<String> hrefs = List.of("/?q=shirt", "/?category=shoes");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).isEmpty();
    }

    // (e) hrefs=[/?page=2,/?page=3] @cur=2 → maxN=3, 3>2 → OptionalInt.of(4)
    @Test
    void hrefsStartAboveCurrentPage_returnsNextPage() {
        List<String> hrefs = List.of("/?page=2", "/?page=3");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 2);
        assertThat(result).hasValue(4);
    }

    // ══════════════════════════════════════════════════════════════════
    // harvey-otras-temporadas — paginación con param `mpage` (colecciones
    // TN que paginan con ?mpage=N en vez de ?page=N, ej. Harvey Willys
    // /otras-temporadas1?mpage=3).
    // ══════════════════════════════════════════════════════════════════

    // (f) hrefs mpage → el helper reconoce mpage= igual que page=
    @Test
    void mpageHrefs_returnsNextPage() {
        List<String> hrefs = List.of("/otras-temporadas1?mpage=1", "/otras-temporadas1?mpage=2",
                "/otras-temporadas1?mpage=3");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 1);
        assertThat(result).hasValue(4);
    }

    // (g) mpage= no debe confundirse: "mpage=2" contiene "page=2" pero el
    // número extraído debe ser el de la paginación real (2), no romperse.
    @Test
    void mpageSinChocarConPage() {
        List<String> hrefs = List.of("/coleccion?mpage=5");
        OptionalInt result = TiendanubePage.resolveNextPageFromHrefs(hrefs, 2);
        assertThat(result).hasValue(6);
    }

    // ── urlPagina: preserva el param de paginación de la base ──────────

    @Test
    void urlPaginaUsaPagePorDefecto() {
        assertThat(TiendanubePage.urlPagina("https://x.com/productos/", 2))
                .isEqualTo("https://x.com/productos/?page=2");
    }

    @Test
    void urlPaginaIncrementaPageExistente() {
        assertThat(TiendanubePage.urlPagina("https://x.com/productos/?page=2", 3))
                .isEqualTo("https://x.com/productos/?page=3");
    }

    @Test
    void urlPaginaPreservaMpage() {
        assertThat(TiendanubePage.urlPagina("https://x.com/otras-temporadas1?mpage=1", 2))
                .isEqualTo("https://x.com/otras-temporadas1?mpage=2");
    }

    @Test
    void urlPaginaPaginaUnoDevuelveBase() {
        assertThat(TiendanubePage.urlPagina("https://x.com/otras-temporadas1?mpage=1", 1))
                .isEqualTo("https://x.com/otras-temporadas1?mpage=1");
    }
}
