package ar.scraper.pages;

import ar.scraper.model.Product;
import ar.scraper.model.SitioCategoriaNodo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping Engine")
@Feature("VTEX Parsing")
@Story("Real API order, partitioning and service filter")
@DisplayName("VtexPage — rows in the order the real API returns them")
class VtexPageRealApiOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOM = "https://www.easy.com.ar";
    private static final String FIXTURE = "/fixtures/vtex-easy/products.json";

    private static JsonNode rows;

    @BeforeAll
    static void loadRows() throws Exception {
        try (InputStream in = VtexPageRealApiOrderTest.class.getResourceAsStream(FIXTURE)) {
            rows = MAPPER.readTree(in);
        }
    }

    private static JsonNode row(String nameStart) {
        for (JsonNode r : rows) {
            if (r.path("productName").asText().startsWith(nameStart)) return r;
        }
        throw new IllegalArgumentException(nameStart);
    }

    private static VtexPage newPage() {
        return new VtexPage(null, 0, "easy", DOM, 100, 100_000_000);
    }

    private static VtexPage pageWithServiceLeaves(Set<Integer> hojas) throws Exception {
        VtexPage page = newPage();
        Field f = VtexPage.class.getDeclaredField("hojasDeServicio");
        f.setAccessible(true);
        f.set(page, hojas);
        return page;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Product> map(VtexPage page, String method, JsonNode json) throws Exception {
        Method m = VtexPage.class.getDeclaredMethod(method, JsonNode.class, String.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(page, json, DOM);
    }

    private static void observeTree(VtexPage page, List<VtexPage.CategoriaVtex> arbol, Set<Integer> hojas)
            throws Exception {
        Field a = VtexPage.class.getDeclaredField("arbolObservado");
        a.setAccessible(true);
        a.set(page, arbol);
        Field h = VtexPage.class.getDeclaredField("hojasDeServicio");
        h.setAccessible(true);
        h.set(page, hojas);
    }

    private static String lastSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return "";
    }

    @Test
    @DisplayName("the fixture keeps the real order: the deepest path is the first entry, the department the last")
    void fixtureIsDeepestFirst() {
        JsonNode inodoro = row("Inodoro con Dep");

        assertThat(inodoro.path("categories").get(0).asText()).isEqualTo("/Baños y Cocinas/Sanitarios/Inodoros/");
        assertThat(inodoro.path("categories").get(2).asText()).isEqualTo("/Baños y Cocinas/");
        assertThat(inodoro.path("categoriesIds").get(0).asText()).isEqualTo("/2/18/133/");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "Inodoro con Dep, Inodoros",
            "Bomba Periférica, Bombas y riego",
            "Clavadora Inalámbrica, Herramientas",
            "Instalación Aa Básica, Instalaciones de electrodomésticos"
    })
    @DisplayName("raw is the last segment of the deepest path, never the department listed last")
    void rawComesFromTheDeepestPath(String nombre, String esperado) throws Exception {
        for (String method : List.of("fromVtex", "fromVtexIO")) {
            Product p = map(newPage(), method, row(nombre)).orElseThrow();

            assertThat(p.categoria()).isEqualTo(esperado);
        }
    }

    @Test
    @DisplayName("on a depth tie the first deepest path wins, not the last one")
    void firstDeepestPathWinsOnTie() throws Exception {
        JsonNode canasto = row("Canasto Organizador Rectangular");

        Product p = map(newPage(), "fromVtex", canasto).orElseThrow();

        assertThat(p.categoria()).isEqualTo("Organizadores de metal y plásticos");
        assertThat(canasto.path("categories").get(5).asText()).endsWith("/Accesorios de Baños/");
    }

    @Test
    @DisplayName("categoryId is the leaf of the first categoriesIds entry on every fixture row")
    void categoryIdMatchesTheLeaf() {
        for (JsonNode r : rows) {
            assertThat(r.path("categoryId").asText())
                    .isEqualTo(lastSegment(r.path("categoriesIds").get(0).asText()));
        }
    }

    @Test
    @DisplayName("the leaf of a row without categoryId is read from the first categoriesIds entry")
    void leafFallsBackToTheFirstCategoriesIdsEntry() throws Exception {
        ObjectNode sinCategoryId = row("Canasto Organizador Rectangular").deepCopy();
        sinCategoryId.remove("categoryId");
        VtexPage pageConServicio = pageWithServiceLeaves(Set.of(206));
        VtexPage pageSinServicio = pageWithServiceLeaves(Set.of(146));

        assertThat(map(pageConServicio, "fromVtex", sinCategoryId)).isEmpty();
        assertThat(map(pageSinServicio, "fromVtex", sinCategoryId)).isPresent();
    }

    @Test
    @DisplayName("a product in a service leaf is dropped and one in a regular leaf is kept")
    void productsInServiceLeavesAreDropped() throws Exception {
        VtexPage page = pageWithServiceLeaves(Set.of(29));

        for (String method : List.of("fromVtex", "fromVtexIO")) {
            assertThat(map(page, method, row("Instalación Aa Básica"))).isEmpty();
            assertThat(map(page, method, row("Inodoro con Dep"))).isPresent();
            assertThat(map(page, method, row("Clavadora Inalámbrica"))).isPresent();
        }
    }

    @Test
    @DisplayName("the service filter looks at the leaf, not at the other branches the product also sits in")
    void onlyTheLeafDecidesService() throws Exception {
        VtexPage page = pageWithServiceLeaves(Set.of(3));

        assertThat(map(page, "fromVtex", row("Instalación Aa Básica"))).isPresent();
    }

    @Test
    @DisplayName("with no service leaves nothing is dropped")
    void noServiceLeavesDropsNothing() throws Exception {
        for (JsonNode r : rows) {
            assertThat(map(newPage(), "fromVtex", r)).isPresent();
        }
    }

    @Test
    @DisplayName("arbolDeCategorias flags the service leaves and links every node to its parent")
    void flattenedTreeCarriesParentsAndServiceFlag() throws Exception {
        List<VtexPage.CategoriaVtex> arbol = List.of(
                new VtexPage.CategoriaVtex(3, "Electrodomésticos", List.of(
                        new VtexPage.CategoriaVtex(29, "Instalaciones de electrodomésticos", List.of()),
                        new VtexPage.CategoriaVtex(30, "Heladeras", List.of()))));
        VtexPage page = newPage();
        observeTree(page, arbol, Set.of(29));

        List<SitioCategoriaNodo> nodos = page.arbolDeCategorias();

        assertThat(nodos).containsExactly(
                new SitioCategoriaNodo(3, null, "Electrodomésticos", false),
                new SitioCategoriaNodo(29, 3, "Instalaciones de electrodomésticos", true),
                new SitioCategoriaNodo(30, 3, "Heladeras", false));
    }

    private static List<VtexPage.CategoriaVtex> unArbol() {
        return List.of(
                new VtexPage.CategoriaVtex(1, "Pinturas", List.of(
                        new VtexPage.CategoriaVtex(10, "Latex", List.of()),
                        new VtexPage.CategoriaVtex(11, "Esmaltes", List.of()))),
                new VtexPage.CategoriaVtex(2, "Baños y Cocinas", List.of(
                        new VtexPage.CategoriaVtex(20, "Sanitarios", List.of(
                                new VtexPage.CategoriaVtex(200, "Inodoros", List.of()),
                                new VtexPage.CategoriaVtex(201, "Bidets", List.of()))))),
                new VtexPage.CategoriaVtex(3, "Herramientas", List.of()));
    }

    private static ToIntFunction<String> totales(Map<String, Integer> porPath) {
        return path -> porPath.getOrDefault(path, 0);
    }

    @Test
    @DisplayName("a top category that fits the window is one partition and its children are not visited")
    void categoryThatFitsIsOnePartition() {
        List<String> visitados = new ArrayList<>();
        ToIntFunction<String> total = path -> {
            visitados.add(path);
            return 100;
        };

        List<String> partes = VtexPage.particionar(unArbol(), total, VtexPage.VENTANA_LEGACY);

        assertThat(partes).containsExactly("/1/", "/2/", "/3/");
        assertThat(visitados).containsExactly("/1/", "/2/", "/3/");
    }

    @Test
    @DisplayName("a category over the window is split into its children, recursively")
    void categoryOverTheWindowIsSplit() {
        ToIntFunction<String> total = totales(Map.of(
                "/1/", VtexPage.VENTANA_LEGACY + 1, "/1/10/", 900, "/1/11/", 800,
                "/2/", 6000, "/2/20/", VtexPage.VENTANA_LEGACY + 1, "/2/20/200/", 2000, "/2/20/201/", 300,
                "/3/", 50));

        List<String> partes = VtexPage.particionar(unArbol(), total, VtexPage.VENTANA_LEGACY);

        assertThat(partes).containsExactly("/1/10/", "/1/11/", "/2/20/200/", "/2/20/201/", "/3/");
    }

    @Test
    @DisplayName("the window is inclusive: exactly VENTANA_LEGACY products is not split")
    void windowBoundaryIsInclusive() {
        ToIntFunction<String> total = totales(Map.of(
                "/1/", VtexPage.VENTANA_LEGACY, "/2/", VtexPage.VENTANA_LEGACY + 1, "/2/20/", 10));

        List<String> partes = VtexPage.particionar(unArbol(), total, VtexPage.VENTANA_LEGACY);

        assertThat(partes).containsExactly("/1/", "/2/20/");
    }

    @Test
    @DisplayName("an empty category is skipped and its children are never asked for")
    void emptyCategoryIsSkipped() {
        List<String> visitados = new ArrayList<>();
        ToIntFunction<String> total = path -> {
            visitados.add(path);
            return path.equals("/3/") ? 10 : 0;
        };

        List<String> partes = VtexPage.particionar(unArbol(), total, VtexPage.VENTANA_LEGACY);

        assertThat(partes).containsExactly("/3/");
        assertThat(visitados).containsExactly("/1/", "/2/", "/3/");
    }

    @Test
    @DisplayName("a leaf larger than the window is kept as one partition, never dropped")
    void oversizedLeafIsKept() {
        ToIntFunction<String> total = totales(Map.of("/3/", VtexPage.VENTANA_LEGACY * 3));

        List<String> partes = VtexPage.particionar(unArbol(), total, VtexPage.VENTANA_LEGACY);

        assertThat(partes).containsExactly("/3/");
    }

    @Test
    @DisplayName("an empty tree yields no partitions")
    void emptyTreeYieldsNoPartitions() {
        assertThat(VtexPage.particionar(List.of(), path -> 10, VtexPage.VENTANA_LEGACY)).isEmpty();
    }

    @Test
    @DisplayName("parseArbol reads ids, trims names, nests children and skips nodes without id")
    void parseArbolReadsTheRealShape() throws Exception {
        JsonNode json = MAPPER.readTree("["
                + "{\"id\":16,\"name\":\" Herramientas \",\"children\":[{\"id\":161,\"name\":\"Taladros\",\"children\":[]}]},"
                + "{\"name\":\"Sin id\"},"
                + "{\"id\":null,\"name\":\"Id nulo\"},"
                + "{\"id\":3,\"name\":\"Electrodomésticos\"}]");

        List<VtexPage.CategoriaVtex> arbol = VtexPage.parseArbol(json);

        assertThat(arbol).containsExactly(
                new VtexPage.CategoriaVtex(16, "Herramientas", List.of(
                        new VtexPage.CategoriaVtex(161, "Taladros", List.of()))),
                new VtexPage.CategoriaVtex(3, "Electrodomésticos", List.of()));
    }

    @Test
    @DisplayName("parseArbol of null or a non-array is empty")
    void parseArbolOfGarbageIsEmpty() throws Exception {
        assertThat(VtexPage.parseArbol(null)).isEmpty();
        assertThat(VtexPage.parseArbol(MAPPER.readTree("{\"id\":1}"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Instalación", "Instalaciones de electrodomésticos", "Instalacion sin tilde", "INSTALACIONES",
            "Armado", "Armados de muebles", "Servicio", "Servicios de obra", "Colocación", "Colocacion de pisos",
            "  Instalación con espacios al inicio"})
    @DisplayName("ServicioPorNombreDeCategoria flags the anchored service prefixes")
    void anchoredPrefixesAreServices(String nombre) {
        assertThat(new ServicioPorNombreDeCategoria().esServicio(nombre)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Herramientas", "Kit de instalación", "Accesorios de Instalación", "Instaladores", "Armadora de cajas",
            "Serviciox", "Colocaciones", "Desarmadores", "Mantenimiento y servicio técnico", ""})
    @DisplayName("ServicioPorNombreDeCategoria ignores the same words in the middle of a name or inside a longer word")
    void unanchoredOrLongerWordsAreNotServices(String nombre) {
        assertThat(new ServicioPorNombreDeCategoria().esServicio(nombre)).isFalse();
    }

    @Test
    @DisplayName("ServicioPorNombreDeCategoria treats null as not a service")
    void nullIsNotAService() {
        assertThat(new ServicioPorNombreDeCategoria().esServicio(null)).isFalse();
    }

    @Test
    @DisplayName("idsDeServicio is inherited by every descendant and does not leak to siblings")
    void serviceFlagIsInheritedByChildren() {
        List<VtexPage.CategoriaVtex> arbol = List.of(
                new VtexPage.CategoriaVtex(1, "Instalaciones", List.of(
                        new VtexPage.CategoriaVtex(11, "Plomería", List.of(
                                new VtexPage.CategoriaVtex(111, "Cañerías", List.of()))),
                        new VtexPage.CategoriaVtex(12, "Electricidad", List.of()))),
                new VtexPage.CategoriaVtex(2, "Plomería", List.of(
                        new VtexPage.CategoriaVtex(21, "Kit de instalación", List.of()),
                        new VtexPage.CategoriaVtex(22, "Armado de tanques", List.of(
                                new VtexPage.CategoriaVtex(221, "Tanques chicos", List.of()))))));

        Set<Integer> ids = VtexPage.idsDeServicio(arbol, new ServicioPorNombreDeCategoria());

        assertThat(ids).containsExactlyInAnyOrder(1, 11, 111, 12, 22, 221);
    }

    @Test
    @DisplayName("idsDeServicio uses the injected criterion instead of a per-site rule")
    void injectedCriterionDecides() {
        List<VtexPage.CategoriaVtex> arbol = List.of(
                new VtexPage.CategoriaVtex(1, "Garantías", List.of(
                        new VtexPage.CategoriaVtex(11, "Extendida", List.of()))),
                new VtexPage.CategoriaVtex(2, "Instalaciones", List.of()));

        Set<Integer> ids = VtexPage.idsDeServicio(arbol, nombre -> nombre.startsWith("Garant"));

        assertThat(ids).containsExactlyInAnyOrder(1, 11);
    }
}
