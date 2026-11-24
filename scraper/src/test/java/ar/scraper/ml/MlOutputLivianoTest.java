package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("The retained ML output drops the per-product scores")
@DisplayName("MlOutputLiviano — everything but the scores survives, and the scores stay a non-empty object")
class MlOutputLivianoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPLETO = """
            {"scores":{"https://a":{"composite":71,"badges":["x"]},"https://b":{"composite":40},"https://c":{}},
             "globalStats":{"mean":3.5},
             "categoriaStats":{"Remera":{"n":12}},
             "tendencias":{"fecha":"2026-11-24","totalProductos":3,"topProductos":[{"url":"https://a"}]},
             "marca":10}
            """;

    private static JsonNode completo() throws Exception {
        return MAPPER.readTree(COMPLETO);
    }

    @Test
    @DisplayName("keeps every field but the scores, in place, and summarises the scores as a total")
    void keepsEverythingButTheScores() throws Exception {
        JsonNode liviano = MlOutputLiviano.aligerar(completo());

        JsonNode esperado = completo();
        ((com.fasterxml.jackson.databind.node.ObjectNode) esperado).set("scores", MAPPER.readTree("{\"total\":3}"));
        assertThat(liviano).isEqualTo(esperado);
        assertThat(liviano.fieldNames()).toIterable()
                .containsExactly("scores", "globalStats", "categoriaStats", "tendencias", "marca");
    }

    @Test
    @DisplayName("the original tree is left untouched")
    void doesNotMutateTheInput() throws Exception {
        JsonNode original = completo();

        MlOutputLiviano.aligerar(original);

        assertThat(original).isEqualTo(completo());
    }

    @Test
    @DisplayName("what /api/tendencias checks stays true: a non-empty object of scores and an object of tendencias")
    void tendenciasValidityIsPreserved() throws Exception {
        JsonNode liviano = MlOutputLiviano.aligerar(completo());

        assertThat(liviano.path("scores").isObject()).isTrue();
        assertThat(liviano.path("scores").isEmpty()).isFalse();
        assertThat(liviano.path("tendencias")).isEqualTo(completo().path("tendencias"));
    }

    @Test
    @DisplayName("an empty scores object stays empty, so the output is still invalid for the readers")
    void emptyScoresStayEmpty() throws Exception {
        JsonNode liviano = MlOutputLiviano.aligerar(MAPPER.readTree("{\"scores\":{},\"tendencias\":{}}"));

        assertThat(liviano.path("scores").isObject()).isTrue();
        assertThat(liviano.path("scores").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("null, a non-object and an output without scores come back as they are")
    void passesThroughWhatHasNoScores() throws Exception {
        JsonNode sinScores = MAPPER.readTree("{\"tendencias\":{}}");
        JsonNode arreglo = MAPPER.readTree("[1,2]");
        JsonNode scoresNoObjeto = MAPPER.readTree("{\"scores\":[1]}");

        assertThat(MlOutputLiviano.aligerar(null)).isNull();
        assertThat(MlOutputLiviano.aligerar(sinScores)).isSameAs(sinScores);
        assertThat(MlOutputLiviano.aligerar(arreglo)).isSameAs(arreglo);
        assertThat(MlOutputLiviano.aligerar(scoresNoObjeto)).isSameAs(scoresNoObjeto);
    }

    @Test
    @DisplayName("applying it twice changes nothing, so a reloaded summary is not summarised again")
    void isIdempotent() throws Exception {
        JsonNode una = MlOutputLiviano.aligerar(completo());

        JsonNode dos = MlOutputLiviano.aligerar(una);

        assertThat(dos).isSameAs(una);
        assertThat(dos.path("scores").path("total").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("an old payload (all the scores) reads back as the summary, without a tree of scores")
    void readsAnOldPayload() throws Exception {
        assertThat(MlOutputLiviano.leer(COMPLETO)).isEqualTo(MlOutputLiviano.aligerar(completo()));
    }

    @Test
    @DisplayName("a new payload (already the summary) reads back unchanged")
    void readsANewPayload() throws Exception {
        String nuevo = MAPPER.writeValueAsString(MlOutputLiviano.aligerar(completo()));

        assertThat(MlOutputLiviano.leer(nuevo)).isEqualTo(MlOutputLiviano.aligerar(completo()));
    }

    @Test
    @DisplayName("payloads that are not an object with scores read as plain JSON")
    void readsOtherShapes() throws Exception {
        assertThat(MlOutputLiviano.leer("[1,2]")).isEqualTo(MAPPER.readTree("[1,2]"));
        assertThat(MlOutputLiviano.leer("{\"tendencias\":{\"a\":1}}")).isEqualTo(MAPPER.readTree("{\"tendencias\":{\"a\":1}}"));
        assertThat(MlOutputLiviano.leer("{\"scores\":{},\"x\":1}")).isEqualTo(MAPPER.readTree("{\"scores\":{},\"x\":1}"));
        assertThat(MlOutputLiviano.leer("{\"scores\":[1,2],\"x\":1}")).isEqualTo(MAPPER.readTree("{\"scores\":[1,2],\"x\":1}"));
    }

    @Test
    @DisplayName("a truncated payload is an error, as it was when the whole tree was parsed")
    void truncatedPayloadFails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MlOutputLiviano.leer("{\"scores\":{\"a\":{\"b\":1}"))
                .isInstanceOf(java.io.IOException.class);
    }
}
