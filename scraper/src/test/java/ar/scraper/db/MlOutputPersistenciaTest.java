package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.ml.MlOutputLiviano;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("ML output")
@Story("ml_output keeps the run summary, not the per-product scores")
@DisplayName("ml_output — the stored payload is the summary, and old payloads still load")
class MlOutputPersistenciaTest extends PostgresTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatabaseService db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        jdbc = new JdbcTemplate(dataSource());
    }

    private static ObjectNode salida(int productos) {
        ObjectNode raiz = MAPPER.createObjectNode();
        ObjectNode scores = raiz.putObject("scores");
        for (int i = 0; i < productos; i++) {
            scores.putObject("https://p/" + i).put("composite", i % 100).putArray("badges").add("x");
        }
        raiz.putObject("tendencias").put("totalProductos", productos).put("fecha", "2026-11-24");
        raiz.putObject("categoriaStats").putObject("Remera").put("n", productos);
        return raiz;
    }

    private String ultimoPayload() {
        return jdbc.queryForObject("SELECT payload FROM ml_output ORDER BY id DESC LIMIT 1", String.class);
    }

    @Test
    @DisplayName("a saved output stores the summary: tendencias and the rest intact, scores as a total")
    void savedOutputIsTheSummary() throws Exception {
        db.guardarMlOutput(salida(500));

        JsonNode guardado = MAPPER.readTree(ultimoPayload());
        assertThat(guardado.path("scores")).isEqualTo(MAPPER.readTree("{\"total\":500}"));
        assertThat(guardado.path("tendencias")).isEqualTo(salida(500).path("tendencias"));
        assertThat(guardado.path("categoriaStats")).isEqualTo(salida(500).path("categoriaStats"));
        assertThat(ultimoPayload().length()).isLessThan(500);
    }

    @Test
    @DisplayName("what is loaded back serves /api/tendencias exactly as the full output did")
    void loadedOutputServesTendencias() {
        db.guardarMlOutput(salida(500));

        JsonNode cargado = db.cargarMlOutput();

        assertThat(cargado.path("scores").isObject()).isTrue();
        assertThat(cargado.path("scores").isEmpty()).isFalse();
        assertThat(cargado.path("tendencias")).isEqualTo(salida(500).path("tendencias"));
        assertThat(cargado).isEqualTo(MlOutputLiviano.aligerar(salida(500)));
    }

    @Test
    @DisplayName("a payload stored before this change, with every score, loads as the same summary")
    void oldPayloadLoads() throws Exception {
        jdbc.update("INSERT INTO ml_output (payload, created_at) VALUES (?, now())",
                MAPPER.writeValueAsString(salida(500)));

        JsonNode cargado = db.cargarMlOutput();

        assertThat(cargado).isEqualTo(MlOutputLiviano.aligerar(salida(500)));
        assertThat(cargado.path("scores").path("total").asInt()).isEqualTo(500);
    }

    @Test
    @DisplayName("an output without scores is still not stored")
    void outputWithoutScoresIsNotStored() throws Exception {
        db.guardarMlOutput(salida(0));
        db.guardarMlOutput(MAPPER.readTree("{\"tendencias\":{}}"));
        db.guardarMlOutput(null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ml_output", Integer.class)).isZero();
        assertThat(db.cargarMlOutput()).isNull();
    }

    @Test
    @DisplayName("only the last ten outputs are kept, newest loaded")
    void keepsTheLastTen() {
        for (int i = 1; i <= 12; i++) db.guardarMlOutput(salida(i));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ml_output", Integer.class)).isEqualTo(10);
        assertThat(db.cargarMlOutput().path("tendencias").path("totalProductos").asInt()).isEqualTo(12);
    }
}
