package ar.scraper.config;

import ar.scraper.api.ApiResponse;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.web.dto.CatalogoDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * The HTTP converter is Boot's Jackson 3 mapper while the code builds its trees with Jackson 2.
 * A unit test that serializes with its own Jackson 2 mapper cannot see what goes over the wire.
 */
@WebMvcTest(controllers = HttpJsonBodyTest.Fixture.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({HttpJsonBodyTest.Fixture.class, LegacyJsonNodeSerializer.class})
@Epic("API envelope")
@Feature("Serialization")
@DisplayName("HTTP JSON body — what Boot's converter writes")
class HttpJsonBodyTest {

    @RestController
    static class Fixture {
        @GetMapping("/fixture/node")
        ApiResponse<ObjectNode> node() {
            ObjectNode n = new ObjectMapper().createObjectNode();
            n.put("z", 1);
            n.putArray("a").add(2.5).add("x");
            n.putNull("n");
            return ApiResponse.ok(n);
        }

        @GetMapping("/fixture/map")
        ApiResponse<Map<String, Object>> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("z", 1);
            m.put("a", 2);
            return ApiResponse.ok(m);
        }

        @GetMapping("/fixture/ml")
        ApiResponse<CatalogoDtos.Ml> ml() {
            return ApiResponse.ok(new CatalogoDtos.Ml("b", List.of(), 1, false, "t", 2, 0.5, "s"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a Jackson 2 JsonNode is written as the JSON it holds, not as a bean")
    void jsonNodeIsWrittenVerbatim() throws Exception {
        mockMvc.perform(get("/fixture/node"))
                .andExpect(content().string("{\"data\":{\"z\":1,\"a\":[2.5,\"x\"],\"n\":null}}"));
    }

    @Test
    @DisplayName("map keys keep insertion order, not alphabetical")
    void mapKeepsInsertionOrder() throws Exception {
        mockMvc.perform(get("/fixture/map"))
                .andExpect(content().string("{\"data\":{\"z\":1,\"a\":2}}"));
    }

    @Test
    @DisplayName("a bean keeps its Jackson 2 key order and the explicit field name")
    void beanKeepsDeclarationOrderAndNames() throws Exception {
        mockMvc.perform(get("/fixture/ml"))
                .andExpect(content().string("{\"data\":{\"badge\":\"b\",\"badges\":[],\"scoreP\":1,"
                        + "\"ofertaReal\":false,\"tendencia\":\"t\",\"pctil\":2,\"segment\":\"s\",\"zScore\":0.5}}"));
    }
}
