package ar.scraper.pages;

import ar.scraper.pages.VtexPage.CategoriaVtex;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Scraping")
@Feature("VTEX storefront pass")
@DisplayName("PasadaDeTienda — listing parse, stock matrix, split, failure")
class PasadaDeTiendaTest {

    private static final String DOM = "https://tienda.test";

    private final List<String> pedidas = new ArrayList<>();

    private static String html(int total, String... items) {
        String results = String.join(",", items);
        return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"__APOLLO_STATE__\":{\"ROOT_QUERY\":{"
                + "\"items({\\\"a\\\":1})\":{\"total\":" + total + ",\"results({\\\"p\\\":1})\":[" + results + "]}}}}}}"
                + "</script></body></html>";
    }

    private static String item(String code, String title, String seller) {
        return "{\"code\":\"" + code + "\",\"item\":{\"title\":\"" + title + "\"},\"seller\":{\"id\":\"" + seller + "\"}}";
    }

    private static CategoriaVtex nodo(int id, String ruta, CategoriaVtex... hijos) {
        return new CategoriaVtex(id, "n" + id, List.of(hijos), DOM + "/" + ruta);
    }

    private PasadaDeTienda pasada(Function<String, String> lector) {
        return new PasadaDeTienda(DOM, "t", url -> { pedidas.add(url); return lector.apply(url); }, () -> { });
    }

    @Test
    @DisplayName("parses total, results, title and keeps only the own seller")
    void parseaElListado() {
        String h = html(2, item("111", "  Heladera Gafa 300 L ", "fravega"), item("222", "Ajeno", "otro"));

        PasadaDeTienda.Pagina p = PasadaDeTienda.parsear(h);

        assertThat(p.total()).isEqualTo(2);
        assertThat(p.resultados()).hasSize(2);
        var r = pasada(u -> h).ejecutar(List.of(nodo(7, "heladeras")), Set.of());
        assertThat(r.titulos()).containsExactly(Map.entry("111", "Heladera Gafa 300 L"));
        assertThat(pedidas).containsExactly(DOM + "/l/heladeras/?vendedor=fravega&page=1");
    }

    @Test
    @DisplayName("paginates until what was announced is captured, and stops on an empty page")
    void paginaHastaCompletar() {
        Function<String, String> l = u -> u.endsWith("page=1") ? html(3, item("1", "a", "fravega"), item("2", "b", "fravega"))
                : u.endsWith("page=2") ? html(3, item("3", "c", "fravega")) : html(3);

        var r = pasada(l).ejecutar(List.of(nodo(7, "x")), Set.of());

        assertThat(pedidas).hasSize(2);
        assertThat(r.listados().get(0).capturados()).isEqualTo(3);
        assertThat(r.listados().get(0).incompleto()).isFalse();
    }

    @Test
    @DisplayName("an empty page before the announced total ends the listing, which is then incomplete")
    void paginaVaciaCorta() {
        Function<String, String> l = u -> u.endsWith("page=1") ? html(100, item("1", "a", "fravega")) : html(100);

        var r = pasada(l).ejecutar(List.of(nodo(7, "x")), Set.of());

        assertThat(pedidas).hasSize(2);
        assertThat(r.listados().get(0).incompleto()).isTrue();
    }

    @Test
    @DisplayName("a listing that announces more than 1,000 is split into its children")
    void partePorLaVentana() {
        Function<String, String> l = u -> u.contains("/l/padre/?") ? html(1500, item("9", "p", "fravega"))
                : u.contains("/l/padre/h1/") ? html(1, item("1", "uno", "fravega"))
                : html(1, item("2", "dos", "fravega"));

        var r = pasada(l).ejecutar(List.of(
                nodo(1, "padre", nodo(11, "padre/h1"), nodo(12, "padre/h2"))), Set.of());

        assertThat(pedidas).containsExactly(
                DOM + "/l/padre/?vendedor=fravega&page=1",
                DOM + "/l/padre/h1/?vendedor=fravega&page=1",
                DOM + "/l/padre/h2/?vendedor=fravega&page=1");
        assertThat(r.listados()).extracting(PasadaDeTienda.Listado::ids)
                .containsExactly(Set.of(11), Set.of(12));
        assertThat(r.titulos()).containsKeys("1", "2", "9");
    }

    @Test
    @DisplayName("excluded nodes and nodes without a URL are never requested")
    void excluidos() {
        var arbol = List.of(nodo(1000312, "servicios"), new CategoriaVtex(5, "sin url", List.of()), nodo(7, "x"));

        pasada(u -> html(0)).ejecutar(arbol, Set.of(1000312));

        assertThat(pedidas).containsExactly(DOM + "/l/x/?vendedor=fravega&page=1");
    }

    @Test
    @DisplayName("stock matrix: listed, absent by API quantity, incomplete listing, uncovered category")
    void matrizDeStock() {
        var completo = new PasadaDeTienda.Listado(Set.of(7), 100, 97);
        var incompleto = new PasadaDeTienda.Listado(Set.of(8), 100, 96);
        var r = new PasadaDeTienda.Resultado(Map.of("1", "titulo"), Map.of(), List.of(completo, incompleto), 2);

        assertThat(r.stock("1", 99, 0)).isTrue();            // listed, even in an uncovered category
        assertThat(r.stock("2", 7, 0)).isFalse();             // absent, quantity 0
        assertThat(r.stock("2", 7, 10)).isFalse();            // absent, quantity 10
        assertThat(r.stock("2", 7, 11)).isNull();             // absent, 11-99
        assertThat(r.stock("2", 7, 99)).isNull();
        assertThat(r.stock("2", 7, 100)).isNull();            // absent, >= 100
        assertThat(r.stock("2", 7, 99999)).isNull();
        assertThat(r.stock("2", 7, null)).isNull();           // no quantity from the API
        assertThat(r.stock("2", 8, 0)).isNull();              // listing captured 96% < 97%
        assertThat(r.stock("2", 99, 0)).isNull();             // no listing covers the category
        assertThat(r.stock("2", null, 0)).isNull();
        assertThat(r.stock(null, 7, 0)).isFalse();            // no code, covered: can only be absent
    }

    @Test
    @DisplayName("a page that cannot be read fails the whole pass")
    void fallaLaPasada() {
        assertThatThrownBy(() -> pasada(u -> "<html>blocked</html>").ejecutar(List.of(nodo(7, "x")), Set.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pasada(u -> { throw new IllegalStateException("HTTP 500"); })
                .ejecutar(List.of(nodo(7, "x")), Set.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pasada(u -> html(0)).ejecutar(List.of(), Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the code is the trailing number of the product link")
    void codigoDelLink() {
        assertThat(PasadaDeTienda.codigoDe("https://x/heladera-gafa-300-l-12345/p")).isEqualTo("12345");
        assertThat(PasadaDeTienda.codigoDe("https://x/sin-numero/p")).isNull();
        assertThat(PasadaDeTienda.codigoDe("")).isNull();
    }

    @Test
    @DisplayName("more pages than the safety cap fails the pass")
    void topeDePaginas() {
        String pagina = html(100000, IntStream.range(0, 15).mapToObj(i -> item("c" + i, "t", "fravega"))
                .collect(Collectors.joining(",")));

        assertThatThrownBy(() -> pasada(u -> pagina).ejecutar(List.of(nodo(7, "x")), Set.of()))
                .hasMessageContaining("páginas");
    }

    private static String itemConColecciones(String code, String seller, String... slugs) {
        String cols = java.util.Arrays.stream(slugs).map(x -> "{\"slug\":\"" + x + "\"}").collect(Collectors.joining(","));
        return "{\"code\":\"" + code + "\",\"item\":{\"title\":\"t" + code + "\"},\"seller\":{\"id\":\"" + seller
                + "\"},\"collections\":[" + cols + "]}";
    }

    @Test
    @DisplayName("cuotas: a verified N-cuotas-sin-interes collection is read; unverified N, other slugs and other sellers are not")
    void leeLasCuotasDeLaColeccion() {
        String h = html(6,
                itemConColecciones("1", "fravega", "3-cuotas-sin-interes", "descuento-1-pago-10-off"),
                itemConColecciones("2", "fravega", "9-cuotas-sin-interes"),
                itemConColecciones("3", "fravega", "18-cuotas-sin-interes"),
                itemConColecciones("4", "fravega", "ofertas-en-12-cuotas"),
                item("5", "sin colecciones", "fravega"),
                itemConColecciones("6", "otro", "12-cuotas-sin-interes"));

        var r = pasada(u -> h).ejecutar(List.of(nodo(7, "x")), Set.of());

        assertThat(r.cuotas()).containsOnly(Map.entry("1", 3), Map.entry("2", 9));
        assertThat(r.titulos()).containsOnlyKeys("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("cuotas: of two verified collections the larger wins")
    void dosColeccionesGanaLaMayor() {
        String h = html(1, itemConColecciones("1", "fravega", "6-cuotas-sin-interes", "12-cuotas-sin-interes"));

        assertThat(pasada(u -> h).ejecutar(List.of(nodo(7, "x")), Set.of()).cuotas()).containsOnly(Map.entry("1", 12));
    }
}
