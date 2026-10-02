package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
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
@Feature("Shopify Parsing")
@Story("JSON to Product mapping")
@DisplayName("ShopifyPage — fromJson mapping")
class ShopifyPageMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOM = "https://shop.com";

    private final ShopifyPage page = new ShopifyPage(null, 0, "Sitio", DOM, 100, 1_000_000);

    @SuppressWarnings("unchecked")
    private Optional<Product> map(String json) throws Exception {
        Method m = ShopifyPage.class.getDeclaredMethod("fromJson", JsonNode.class, String.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(page, MAPPER.readTree(json), DOM);
    }

    private static String product(String extra) {
        return "{\"title\":\" Remera Hombre \",\"handle\":\"remera\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static final String VARIANT =
            "\"variants\":[{\"price\":\"5.000,00\",\"compare_at_price\":\"8.000,00\",\"option1\":\"M\"}]";

    @Test
    @DisplayName("maps the whole product: url, discounted price, category, gender and sizes")
    void fullMapping() throws Exception {
        String json = product("\"product_type\":\" Remeras \"," + VARIANT + ","
                + "\"images\":[{\"src\":\"//cdn.com/a_small.jpg\"}],"
                + "\"options\":[{\"name\":\"Talle\",\"values\":[\"S\",\" \",\"M\"]}]");

        assertThat(map(json)).contains(Product.builder().sitio("Sitio").nombre("Remera Hombre")
                .precio(5000).precioOriginal(8000.0).url(DOM + "/products/remera")
                .imagenUrl("https://cdn.com/a_800x.jpg").categoria("Remeras").genero("hombre")
                .talles(List.of("S", "M")).build());
    }

    @Test
    @DisplayName("image thumbnails are rewritten to _800x and protocol-relative urls get https")
    void imageRewrite() throws Exception {
        for (String[] c : new String[][] {
                {"https://cdn.com/a_100x100.png", "https://cdn.com/a_800x.png"},
                {"https://cdn.com/a_100x.png", "https://cdn.com/a_800x.png"},
                {"https://cdn.com/a_compact.png", "https://cdn.com/a_800x.png"},
                {"https://cdn.com/a_thumb.png", "https://cdn.com/a_800x.png"},
                {"https://cdn.com/a_icon.png", "https://cdn.com/a_800x.png"},
                {"https://cdn.com/a_big.png", "https://cdn.com/a_big.png"},
                {"//cdn.com/a.png", "https://cdn.com/a.png"}}) {
            String json = product(VARIANT + ",\"images\":[{\"src\":\"" + c[0] + "\"}]");
            assertThat(map(json)).map(Product::imagenUrl).contains(c[1]);
        }
    }

    @Test
    @DisplayName("no images means an empty image url")
    void noImages() throws Exception {
        assertThat(map(product(VARIANT + ",\"images\":[]"))).map(Product::imagenUrl).contains("");
    }

    @Test
    @DisplayName("compare_at_price: 'null', blank, junk and not-greater values are handled as-is")
    void comparePrice() throws Exception {
        for (String[] c : new String[][] {
                {"null", null}, {"", null}, {"abc", null}, {"6000", "6000.0"}, {"4000", "4000.0"}}) {
            String v = "\"variants\":[{\"price\":\"5000\",\"compare_at_price\":\"" + c[0] + "\"}]";
            Double esperado = c[1] == null ? null : Double.valueOf(c[1]);
            assertThat(map(product(v))).map(Product::precioOriginal).isEqualTo(Optional.ofNullable(esperado));
        }
    }

    @Test
    @DisplayName("no variants, or a price out of range, rejects the product")
    void rejections() throws Exception {
        assertThat(map(product("\"variants\":[]"))).isEmpty();
        assertThat(map(product(""))).isEmpty();
        assertThat(map(product("\"variants\":[{\"price\":\"99\"}]"))).isEmpty();
        assertThat(map(product("\"variants\":[{\"price\":\"100\"}]"))).isPresent();
        assertThat(map(product("\"variants\":[{\"price\":\"1000001\"}]"))).isEmpty();
        assertThat(map(product("\"variants\":[{\"price\":\"gratis\"}]"))).isEmpty();
        assertThat(map("{\"title\":\" \",\"variants\":[{\"price\":\"500\"}]}")).isEmpty();
    }

    @Test
    @DisplayName("sizes: option values, else variants' option1 (minus 'default title'), else the first option")
    void sizeStrategies() throws Exception {
        String sinOptionTalle = product("\"options\":[{\"name\":\"Color\",\"values\":[\"Rojo\"]}],"
                + "\"variants\":[{\"price\":\"500\",\"option1\":\"Default Title\"},"
                + "{\"price\":\"500\",\"option1\":\"L\"},{\"price\":\"500\",\"option1\":\"L\"},"
                + "{\"price\":\"500\",\"option1\":\"XL\"}]");
        assertThat(map(sinOptionTalle)).map(Product::talles).contains(List.of("L", "XL"));

        String soloDefault = product("\"options\":[{\"name\":\"Color\",\"values\":[\"Rojo\",\" \",\"Azul\"]}],"
                + "\"variants\":[{\"price\":\"500\",\"option1\":\"Default Title\"}]");
        assertThat(map(soloDefault)).map(Product::talles).contains(List.of("Rojo", "Azul"));

        String nada = product("\"variants\":[{\"price\":\"500\",\"option1\":\"\"}]");
        assertThat(map(nada)).map(Product::talles).contains(List.of());

        String optionS = product("\"options\":[{\"name\":\"S\",\"values\":[\"1\",\"2\"]}],"
                + "\"variants\":[{\"price\":\"500\"}]");
        assertThat(map(optionS)).map(Product::talles).contains(List.of("1", "2"));
    }

    @Test
    @DisplayName("gender: unisex anywhere wins; otherwise one side only; both sides is unisex; tags as array or csv")
    void genderRules() throws Exception {
        assertThat(gender("Buzo", "\"product_type\":\"hombre\",\"tags\":\"mujer, Unisex\"")).isEqualTo("unisex");
        assertThat(gender("Buzo Hombre", "\"tags\":[\"x\"]")).isEqualTo("hombre");
        assertThat(gender("Buzo", "\"tags\":[\"Mujeres\"]")).isEqualTo("mujer");
        assertThat(gender("Buzo", "\"tags\":\"varones, damas\"")).isEqualTo("unisex");
        assertThat(gender("Buzo", "\"product_type\":\"damas\"")).isEqualTo("mujer");
        assertThat(gender("Buzo", "\"tags\":\"nada\"")).isEmpty();
        assertThat(gender("Buzo", "\"tags\":7")).isEmpty();
    }

    private String gender(String title, String extra) throws Exception {
        String json = "{\"title\":\"" + title + "\"," + extra + ",\"variants\":[{\"price\":\"500\"}]}";
        return map(json).orElseThrow().genero();
    }
}
