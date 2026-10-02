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
@Feature("TiendaNube Parsing")
@Story("JSON to Product mapping")
@DisplayName("TiendanubePage — fromApi / fromJs mapping")
class TiendanubePageApiMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOM = "https://shop.com";

    private final TiendanubePage page = new TiendanubePage(null, 0, "Sitio", DOM + "/productos/", 100, 1_000_000);

    @SuppressWarnings("unchecked")
    private Optional<Product> fromApi(String json) throws Exception {
        Method m = TiendanubePage.class.getDeclaredMethod("fromApi", JsonNode.class, String.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(page, MAPPER.readTree(json), DOM);
    }

    @SuppressWarnings("unchecked")
    private Optional<Product> fromJs(String json) throws Exception {
        Method m = TiendanubePage.class.getDeclaredMethod("fromJs", JsonNode.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(page, MAPPER.readTree(json));
    }

    private static String product(String extra) {
        return "{\"name\":\" Remera Hombre \",\"canonical_url\":\"/productos/remera\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static final String VARIANT =
            "\"variants\":[{\"price\":\"5.000,00\",\"compare_at_price\":\"8.000,00\"}]";

    @Test
    @DisplayName("maps the whole product: url, prices, image cleanup, category, gender, attribute sizes")
    void fullMapping() throws Exception {
        String json = product("\"images\":[{\"src\":\"//cdn.com/a-480x480.jpg\"}],"
                + "\"categories\":[{\"name\":\" Remeras \"},{\"name\":\"Otra\"}],"
                + VARIANT + ","
                + "\"attributes\":[{\"name\":\"Talle\",\"values\":[\"S\",\" \",\"M\"]}]");

        assertThat(fromApi(json)).contains(Product.builder().sitio("Sitio").nombre("Remera Hombre")
                .precio(5000).precioOriginal(8000.0).url(DOM + "/productos/remera")
                .imagenUrl("https://cdn.com/a.jpg").categoria("Remeras").genero("hombre")
                .talles(List.of("S", "M")).build());
    }

    @Test
    @DisplayName("image: first of images, else variant image, else featured_image, else main_image")
    void imageFallbackChain() throws Exception {
        String conImagen = product("\"images\":[{\"src\":\"https://i.com/1.png\"}],"
                + "\"variants\":[{\"price\":\"500\",\"image\":{\"src\":\"https://i.com/v.png\"}}]");
        String conVariante = product("\"images\":[],"
                + "\"variants\":[{\"price\":\"500\",\"image\":{\"src\":\"https://i.com/v.png\"}}]");
        String conFeatured = product("\"featured_image\":\"https://i.com/f.png\",\"main_image\":\"https://i.com/m.png\","
                + "\"variants\":[{\"price\":\"500\"}]");
        String conMain = product("\"main_image\":\"https://i.com/m.png\",\"variants\":[{\"price\":\"500\"}]");
        String ninguna = product("\"variants\":[{\"price\":\"500\"}]");

        assertThat(fromApi(conImagen)).map(Product::imagenUrl).contains("https://i.com/1.png");
        assertThat(fromApi(conVariante)).map(Product::imagenUrl).contains("https://i.com/v.png");
        assertThat(fromApi(conFeatured)).map(Product::imagenUrl).contains("https://i.com/f.png");
        assertThat(fromApi(conMain)).map(Product::imagenUrl).contains("https://i.com/m.png");
        assertThat(fromApi(ninguna)).map(Product::imagenUrl).contains("");
    }

    @Test
    @DisplayName("image: -WxH and -thumb suffixes are stripped, other names are untouched")
    void imageSuffixes() throws Exception {
        for (String[] c : new String[][] {
                {"https://i.com/a-1024x768.webp", "https://i.com/a.webp"},
                {"https://i.com/a-thumb.jpeg", "https://i.com/a.jpeg"},
                {"https://i.com/a-big.png", "https://i.com/a-big.png"}}) {
            String json = product("\"images\":[{\"src\":\"" + c[0] + "\"}],\"variants\":[{\"price\":\"500\"}]");
            assertThat(fromApi(json)).map(Product::imagenUrl).contains(c[1]);
        }
    }

    @Test
    @DisplayName("compare_at_price handling and rejections match the Shopify rules")
    void priceRules() throws Exception {
        assertThat(fromApi(product("\"variants\":[{\"price\":\"500\",\"compare_at_price\":\"null\"}]")))
                .map(Product::precioOriginal).isEqualTo(Optional.empty());
        assertThat(fromApi(product("\"variants\":[{\"price\":\"500\",\"compare_at_price\":\"900\"}]")))
                .map(Product::precioOriginal).contains(900.0);
        assertThat(fromApi(product("\"variants\":[]"))).isEmpty();
        assertThat(fromApi(product(""))).isEmpty();
        assertThat(fromApi(product("\"variants\":[{\"price\":\"99\"}]"))).isEmpty();
        assertThat(fromApi(product("\"variants\":[{\"price\":\"100\"}]"))).isPresent();
        assertThat(fromApi(product("\"variants\":[{\"price\":\"1000001\"}]"))).isEmpty();
        assertThat(fromApi("{\"name\":\"\",\"variants\":[{\"price\":\"500\"}]}")).isEmpty();
    }

    @Test
    @DisplayName("category is the first category name, empty without categories")
    void category() throws Exception {
        assertThat(fromApi(product("\"categories\":[],\"variants\":[{\"price\":\"500\"}]")))
                .map(Product::categoria).contains("");
        assertThat(fromApi(product("\"variants\":[{\"price\":\"500\"}]"))).map(Product::categoria).contains("");
    }

    @Test
    @DisplayName("sizes: attributes, else variant values named like a size, else first value of first variant")
    void sizeStrategies() throws Exception {
        String porVariante = product("\"variants\":[{\"price\":\"500\",\"values\":["
                + "{\"name\":\"Color\",\"value\":\"Rojo\"},{\"name\":\"Talle\",\"value\":\"L\"}]},"
                + "{\"price\":\"500\",\"values\":[{\"name\":\"Talle\",\"value\":\"L\"}]},"
                + "{\"price\":\"500\",\"values\":[{\"name\":\"Size\",\"value\":\"XL\"}]}]");
        assertThat(fromApi(porVariante)).map(Product::talles).contains(List.of("L", "XL"));

        String primerValor = product("\"variants\":[{\"price\":\"500\",\"values\":[{\"name\":\"Color\",\"value\":\"Rojo\"}]},"
                + "{\"price\":\"500\",\"values\":[{\"name\":\"Color\",\"value\":\"Azul\"}]},"
                + "{\"price\":\"500\",\"values\":[]}]");
        assertThat(fromApi(primerValor)).map(Product::talles).contains(List.of("Rojo", "Azul"));

        String unico = product("\"variants\":[{\"price\":\"500\",\"values\":[{\"name\":\"Color\",\"value\":\"Unique\"}]}]");
        assertThat(fromApi(unico)).map(Product::talles).contains(List.of());

        String sinNada = product("\"variants\":[{\"price\":\"500\"}]");
        assertThat(fromApi(sinNada)).map(Product::talles).contains(List.of());
    }

    @Test
    @DisplayName("gender: name, category names and tags (csv or array); unisex wins; both sides is unisex")
    void genderRules() throws Exception {
        assertThat(gender("Buzo", "\"categories\":[{\"name\":\"Mujer\"}]")).isEqualTo("mujer");
        assertThat(gender("Buzo Hombre", "\"tags\":[\"x\"]")).isEqualTo("hombre");
        assertThat(gender("Buzo", "\"tags\":\"hombre, Unisex\"")).isEqualTo("unisex");
        assertThat(gender("Buzo", "\"tags\":[\"Varones\",\"Damas\"]")).isEqualTo("unisex");
        assertThat(gender("Buzo", "\"tags\":\"nada\"")).isEmpty();
        assertThat(gender("Buzo", "\"tags\":7")).isEmpty();
    }

    private String gender(String name, String extra) throws Exception {
        String json = "{\"name\":\"" + name + "\"," + extra + ",\"variants\":[{\"price\":\"500\"}]}";
        return fromApi(json).orElseThrow().genero();
    }

    @Test
    @DisplayName("fromJs maps the extractor payload: absolute url, https image, sizes, gender, no category")
    void fromJsMapping() throws Exception {
        String json = "{\"nombre\":\" Zapa \",\"precio\":\"$ 5.000\",\"compare\":\" $8.000 \","
                + "\"url\":\"/productos/zapa\",\"img\":\" //cdn.com/z.jpg \","
                + "\"talles\":[\"40\",\" \",\"41\"],\"genero\":\" mujer \"}";

        assertThat(fromJs(json)).contains(Product.builder().sitio("Sitio").nombre("Zapa")
                .precio(5000).precioOriginal(8000.0).url(DOM + "/productos/zapa")
                .imagenUrl("https://cdn.com/z.jpg").categoria("").genero("mujer")
                .talles(List.of("40", "41")).build());
    }

    @Test
    @DisplayName("fromJs rejects blank name, unparseable or out-of-range price")
    void fromJsRejections() throws Exception {
        assertThat(fromJs("{\"nombre\":\"\",\"precio\":\"$ 500\"}")).isEmpty();
        assertThat(fromJs("{\"nombre\":\"X\",\"precio\":\"gratis\"}")).isEmpty();
        assertThat(fromJs("{\"nombre\":\"X\",\"precio\":\"$ 99\"}")).isEmpty();
        assertThat(fromJs("{\"nombre\":\"X\",\"precio\":\"$ 1.000.001\"}")).isEmpty();
        assertThat(fromJs("{\"nombre\":\"X\",\"precio\":\"$ 500\"}")).map(Product::precioOriginal)
                .isEqualTo(Optional.empty());
    }
}
