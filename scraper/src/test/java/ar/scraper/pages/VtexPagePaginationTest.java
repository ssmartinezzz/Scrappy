package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-failing-site-scrapers, T4 — Sporting/VtexPage scraped 250 of 7151
 * products: {@code scrapeApiLegacy} broke silently on a non-JSON body and
 * {@code MAX_PRODUCTS = 2500} capped it well under the real catalog (the live
 * API answers past {@code _from=2500}; header {@code resources: 0-49/7151}).
 *
 * <p>These test the two PURE decisions extracted out of the fix — no
 * network, no Playwright — per the existing TN pagination test pattern
 * ({@code TiendanubePagePaginationTest}).</p>
 */
@Epic("Scraping Engine")
@Feature("VTEX Parsing")
@Story("Legacy catalog API pagination")
@DisplayName("VtexPage — legacy pagination decisions")
class VtexPagePaginationTest {

    // ── parseResourcesTotal ─────────────────────────────────────────────

    @Test
    @DisplayName("parsea el total desde el header resources '0-49/7151'")
    void parseResourcesTotal_headerNormal() {
        OptionalInt total = VtexPage.parseResourcesTotal(Map.of("resources", "0-49/7151"));
        assertThat(total).hasValue(7151);
    }

    @Test
    @DisplayName("el nombre del header no distingue mayúsculas")
    void parseResourcesTotal_headerCaseInsensitive() {
        OptionalInt total = VtexPage.parseResourcesTotal(Map.of("Resources", "2500-2549/7151"));
        assertThat(total).hasValue(7151);
    }

    @Test
    @DisplayName("sin header 'resources', el total es desconocido")
    void parseResourcesTotal_sinHeader() {
        OptionalInt total = VtexPage.parseResourcesTotal(Map.of("content-type", "application/json"));
        assertThat(total).isEmpty();
    }

    @Test
    @DisplayName("un header 'resources' sin '/' no rompe, queda desconocido")
    void parseResourcesTotal_headerMalformado() {
        OptionalInt total = VtexPage.parseResourcesTotal(Map.of("resources", "garbage"));
        assertThat(total).isEmpty();
    }

    @Test
    @DisplayName("headers null no rompe, queda desconocido")
    void parseResourcesTotal_headersNull() {
        assertThat(VtexPage.parseResourcesTotal(null)).isEmpty();
    }

    // ── continuaPaginando ────────────────────────────────────────────────

    @Test
    @DisplayName("una página llena y sin total conocido todavía sigue paginando")
    void continuaPaginando_paginaLlenaSinTotal() {
        assertThat(VtexPage.continuaPaginando(50, 0, OptionalInt.empty())).isTrue();
    }

    @Test
    @DisplayName("una página corta (menos que PAGE_SIZE) es la última — no sigue")
    void continuaPaginando_paginaCorta() {
        assertThat(VtexPage.continuaPaginando(37, 2500, OptionalInt.of(2537))).isFalse();
    }

    @Test
    @DisplayName("llena pero el próximo from ya alcanza el total conocido — no sigue")
    void continuaPaginando_alcanzaElTotalConocido() {
        // from=7101, PAGE_SIZE=50 → próximo from sería 7151, que ya cubre el total 7151
        assertThat(VtexPage.continuaPaginando(50, 7101, OptionalInt.of(7151))).isFalse();
    }

    @Test
    @DisplayName("llena y el total conocido todavía queda lejos — sigue")
    void continuaPaginando_totalLejano() {
        assertThat(VtexPage.continuaPaginando(50, 2500, OptionalInt.of(7151))).isTrue();
    }

    // ── particionar: la API legacy da HTTP 400 pasando _from=2549 ───────

    private static VtexPage.CategoriaVtex cat(int id, VtexPage.CategoriaVtex... hijos) {
        return new VtexPage.CategoriaVtex(id, List.of(hijos));
    }

    /** Árbol real de Sporting (2026-09-28), con INDUMENTARIA partida en dos hijos. */
    private static final Map<String, Integer> TOTALES = Map.of(
            "/106/", 7146, "/106/107/", 2403, "/106/108/", 2638,
            "/106/108/1/", 1500, "/106/108/2/", 1138, "/106/109/", 1955, "/106/185/", 0);

    @Test
    @DisplayName("baja por el árbol hasta que cada parte entra en la ventana, y saltea las vacías")
    void particionar_bajaHastaQueEntre() {
        var arbol = List.of(cat(106, cat(107), cat(108, cat(1), cat(2)), cat(109), cat(185)));

        List<String> partes = VtexPage.particionar(arbol, TOTALES::get, 2500);

        assertThat(partes).containsExactly("/106/107/", "/106/108/1/", "/106/108/2/", "/106/109/");
    }

    @Test
    @DisplayName("una hoja más grande que la ventana se crawlea igual: lo que entre es mejor que nada")
    void particionar_hojaGrandeSeQueda() {
        List<String> partes = VtexPage.particionar(List.of(cat(106)), p -> 9000, 2500);

        assertThat(partes).containsExactly("/106/");
    }

    @Test
    @DisplayName("parsea el árbol de /category/tree/N")
    void parseArbol_idsEHijos() throws Exception {
        var json = new ObjectMapper().readTree("""
                [{"id":106,"name":"SPORTING","children":[{"id":107,"name":"CALZADO","children":[]}]}]""");

        assertThat(VtexPage.parseArbol(json)).containsExactly(cat(106, cat(107)));
    }
}
