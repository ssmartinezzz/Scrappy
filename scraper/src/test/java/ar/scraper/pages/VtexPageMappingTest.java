package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping Engine")
@Feature("VTEX Parsing")
@Story("JSON to Product mapping")
@DisplayName("VtexPage — fromVtex / fromVtexIO mapping")
class VtexPageMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOM = "https://shop.com";

    private final VtexPage page = new VtexPage(null, 0, "Sitio", DOM, 100, 1_000_000);

    @SuppressWarnings("unchecked")
    private Optional<Product> map(String method, String json) throws Exception {
        Method m = VtexPage.class.getDeclaredMethod(method, com.fasterxml.jackson.databind.JsonNode.class, String.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(page, MAPPER.readTree(json), DOM);
    }

    private static String product(String head, String items, String tail) {
        return "{" + head + ",\"items\":" + items + tail + "}";
    }

    private static final String ONE_OFFER =
            "[{\"images\":[{\"imageUrl\":\"https://img.com/a.jpg?v=1&w=2\"},{\"imageUrl\":\"https://img.com/b.jpg\"}],"
                    + "\"sellers\":[{\"commertialOffer\":{\"Price\":0,\"ListPrice\":9000}},"
                    + "{\"commertialOffer\":{\"Price\":5000,\"ListPrice\":8000}},"
                    + "{\"commertialOffer\":{\"Price\":4000,\"ListPrice\":8000}}]}]";

    @Test
    @DisplayName("legacy keeps the query string of the image and takes the first positive offer")
    void legacyKeepsImageQueryString() throws Exception {
        String json = product("\"productName\":\" Remera Hombre \",\"linkText\":\"remera-1\","
                + "\"categories\":[\"/Ropa/\",\"/Ropa/Remeras/\"]", ONE_OFFER, "");

        Product p = map("fromVtex", json).orElseThrow();

        assertThat(p).isEqualTo(Product.builder().sitio("Sitio").nombre("Remera Hombre")
                .precio(5000).precioOriginal(8000.0).url(DOM + "/remera-1/p")
                .imagenUrl("https://img.com/a.jpg?v=1&w=2").categoria("Remeras")
                .genero("hombre").talles(List.of()).build());
    }

    @Test
    @DisplayName("IO strips everything after '?' from the image")
    void ioStripsImageQueryString() throws Exception {
        String json = product("\"productName\":\"Remera\",\"linkText\":\"remera-1\","
                + "\"categories\":[\"/Ropa/Remeras/\"]", ONE_OFFER, "");

        Product p = map("fromVtexIO", json).orElseThrow();

        assertThat(p.imagenUrl()).isEqualTo("https://img.com/a.jpg");
        assertThat(p.precio()).isEqualTo(5000);
        assertThat(p.precioOriginal()).isEqualTo(8000.0);
        assertThat(p.categoria()).isEqualTo("Remeras");
    }

    @Test
    @DisplayName("legacy falls back to 'name' when productName is blank; IO does not")
    void nameFallbackOnlyInLegacy() throws Exception {
        String json = product("\"productName\":\"  \",\"name\":\"Zapatilla\",\"linkText\":\"z\"",
                ONE_OFFER, "");

        assertThat(map("fromVtex", json)).map(Product::nombre).contains("Zapatilla");
        assertThat(map("fromVtexIO", json)).isEmpty();
    }

    @Test
    @DisplayName("both reject a product with no name at all")
    void blankNameIsRejected() throws Exception {
        String json = product("\"productName\":\"\",\"name\":\"\"", ONE_OFFER, "");

        assertThat(map("fromVtex", json)).isEmpty();
        assertThat(map("fromVtexIO", json)).isEmpty();
    }

    @Test
    @DisplayName("IO falls back to the last categoryTree name when categories yield nothing; legacy does not")
    void categoryTreeFallbackOnlyInIo() throws Exception {
        String tree = ",\"categoryTree\":[{\"name\":\"Ropa\"},{\"name\":\" Buzos \"}]";
        String sinCats = product("\"productName\":\"Buzo\"", ONE_OFFER, tree);
        String catsEnBlanco = product("\"productName\":\"Buzo\",\"categories\":[\"/ / \"]", ONE_OFFER, tree);

        assertThat(map("fromVtexIO", sinCats)).map(Product::categoria).contains("Buzos");
        assertThat(map("fromVtexIO", catsEnBlanco)).map(Product::categoria).contains("Buzos");
        assertThat(map("fromVtex", sinCats)).map(Product::categoria).contains("");
        assertThat(map("fromVtex", catsEnBlanco)).map(Product::categoria).contains("");
    }

    @Test
    @DisplayName("categories win over categoryTree, and only the last non-blank segment of the last entry counts")
    void categoriesTakePrecedence() throws Exception {
        String json = product("\"productName\":\"Buzo\",\"categories\":[\"/A/\",\"/Ropa//Buzos/ \"]",
                ONE_OFFER, ",\"categoryTree\":[{\"name\":\"Otra\"}]");

        assertThat(map("fromVtexIO", json)).map(Product::categoria).contains("Buzos");
        assertThat(map("fromVtex", json)).map(Product::categoria).contains("Buzos");
    }

    @Test
    @DisplayName("no items, no image and url stays empty without linkText")
    void noItemsMeansNoPriceAndIsRejected() throws Exception {
        String json = "{\"productName\":\"X\",\"items\":[]}";

        assertThat(map("fromVtex", json)).isEmpty();
        assertThat(map("fromVtexIO", json)).isEmpty();
    }

    @Test
    @DisplayName("blank linkText gives an empty url; no images gives an empty image")
    void blankLinkTextAndNoImages() throws Exception {
        String items = "[{\"images\":[],\"sellers\":[{\"commertialOffer\":{\"Price\":300,\"ListPrice\":300}}]}]";
        String json = product("\"productName\":\"X\",\"linkText\":\"\"", items, "");

        for (String m : List.of("fromVtex", "fromVtexIO")) {
            Product p = map(m, json).orElseThrow();
            assertThat(p.url()).isEmpty();
            assertThat(p.imagenUrl()).isEmpty();
            assertThat(p.precioOriginal()).isNull();
        }
    }

    @Test
    @DisplayName("the first positive offer comes from the first item that has one; image from item 0 only")
    void priceSearchSkipsItemsWithoutSellers() throws Exception {
        String items = "[{\"images\":[],\"sellers\":\"no\"},"
                + "{\"images\":[{\"imageUrl\":\"https://img.com/second.jpg\"}],"
                + "\"sellers\":[{\"commertialOffer\":{\"Price\":700,\"ListPrice\":900}}]}]";
        String json = product("\"productName\":\"X\",\"linkText\":\"x\"", items, "");

        for (String m : List.of("fromVtex", "fromVtexIO")) {
            Product p = map(m, json).orElseThrow();
            assertThat(p.precio()).isEqualTo(700);
            assertThat(p.precioOriginal()).isEqualTo(900.0);
            assertThat(p.imagenUrl()).isEmpty();
        }
    }

    @Test
    @DisplayName("prices outside [precioMin, precioMax] are rejected; the bounds are inclusive")
    void priceBounds() throws Exception {
        for (String m : List.of("fromVtex", "fromVtexIO")) {
            assertThat(map(m, offer(99.0))).isEmpty();
            assertThat(map(m, offer(100.0))).isPresent();
            assertThat(map(m, offer(1_000_000.0))).isPresent();
            assertThat(map(m, offer(1_000_000.5))).isEmpty();
        }
    }

    private static String offer(double price) {
        return "{\"productName\":\"X\",\"items\":[{\"sellers\":[{\"commertialOffer\":{\"Price\":"
                + price + ",\"ListPrice\":0}}]}]}";
    }

    @Test
    @DisplayName("sizes come from skuSpecifications, gender from specificationGroups")
    void sizesAndGenderFromSpecs() throws Exception {
        String tail = ",\"skuSpecifications\":[{\"field\":{\"name\":\"Talle\"},"
                + "\"values\":[{\"name\":\"S\"},{\"name\":\" \"},{\"name\":\"M\"}]}],"
                + "\"specificationGroups\":[{\"specifications\":[{\"name\":\"Género\",\"values\":[\"Mujeres\"]}]}]";
        String json = product("\"productName\":\"Remera Hombre\"", ONE_OFFER, tail);

        for (String m : List.of("fromVtex", "fromVtexIO")) {
            Product p = map(m, json).orElseThrow();
            assertThat(p.talles()).containsExactly("S", "M");
            assertThat(p.genero()).isEqualTo("mujer");
        }
    }

    @Test
    @DisplayName("malformed input yields an empty Optional instead of throwing")
    void malformedIsEmpty() throws Exception {
        assertThat(map("fromVtex", "[]")).isEmpty();
        assertThat(map("fromVtexIO", "{\"productName\":\"X\",\"items\":{}}")).isEmpty();
    }
}
