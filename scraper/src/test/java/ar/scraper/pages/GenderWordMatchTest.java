package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping Engine")
@Feature("Gender detection")
@Story("Male words match whole words only")
@DisplayName("Gender — male words are whole words, not substrings")
class GenderWordMatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOM = "https://shop.com";

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "remera femenina, mujer",
            "remera manga larga mujer, mujer",
            "women's tee, mujer",
            "female, mujer",
            "remera hombre, hombre",
            "men's tee, hombre",
            "camisa caballeros, hombre",
            "pantalones masculinos, hombre",
            "remera manga corta, ''",
    })
    void catalogJson(String fuente, String esperado) {
        assertThat(CatalogJson.genero(List.of(fuente))).isEqualTo(esperado);
    }

    @Test
    @DisplayName("VTEX: a 'Femenino' spec value is mujer, and 'manga' in the name is not man")
    void vtex() throws Exception {
        String items = "[{\"sellers\":[{\"commertialOffer\":{\"Price\":5000,\"ListPrice\":0}}]}]";
        String porSpec = "{\"productName\":\"Remera\",\"items\":" + items
                + ",\"specificationGroups\":[{\"specifications\":[{\"name\":\"Género\",\"values\":[\"Femenino\"]}]}]}";
        String porNombre = "{\"productName\":\"Buzo Manga Larga Mujer\",\"items\":" + items + "}";

        for (String m : List.of("fromVtex", "fromVtexIO")) {
            assertThat(vtex(m, porSpec).genero()).isEqualTo("mujer");
            assertThat(vtex(m, porNombre).genero()).isEqualTo("mujer");
        }
    }

    @SuppressWarnings("unchecked")
    private static Product vtex(String method, String json) throws Exception {
        VtexPage page = new VtexPage(null, 0, "Sitio", DOM, 100, 1_000_000);
        Method m = VtexPage.class.getDeclaredMethod(method, JsonNode.class, String.class);
        m.setAccessible(true);
        return ((Optional<Product>) m.invoke(page, MAPPER.readTree(json), DOM)).orElseThrow();
    }
}
