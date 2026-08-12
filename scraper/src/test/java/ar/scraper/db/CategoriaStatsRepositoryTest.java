package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 3 (V16, design DD6).
 *
 * <p>{@code categoria_stats} moves from a {@code payload TEXT} JSON blob to
 * 12 typed columns with a real FK to {@code categoria(nombre)} (V13's closed
 * vocabulary). Two behaviors this pins:</p>
 *
 * <ol>
 *   <li>the 12 fields bind and round-trip through typed columns, not a
 *       string blob;</li>
 *   <li>a non-canonical category key is dropped <b>per key</b>, not by
 *       rolling back the whole batch — {@code CategoriaStatsRepository}'s
 *       {@code catch (Exception)} used to nuke every category's stats for
 *       the run over one stray key (the project's known swallowed-SQL-error
 *       failure mode, one table over from the upsert).</li>
 * </ol>
 */
@Epic("Persistence")
@Feature("categoria_stats")
@Story("V16 — flattened, typed, FK'd categoria_stats")
@DisplayName("CategoriaStatsRepository — 12 typed columns, per-key FK filtering")
class CategoriaStatsRepositoryTest extends PostgresTestBase {

    private DatabaseService db;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("los 12 campos tipados se guardan y se leen de vuelta correctamente")
    void los12CamposTipadosRoundTripean() throws Exception {
        ObjectNode stats = mapper.createObjectNode();
        ObjectNode remera = stats.putObject("Remera");
        remera.put("n", 42);
        remera.put("mean", 15000);
        remera.put("median", 14500);
        remera.put("mode", 12000);
        remera.put("std", 3200);
        remera.put("cv", 21.3);
        remera.put("q1", 11000);
        remera.put("q3", 18000);
        remera.put("iqr", 7000);
        remera.put("mad", 2500);
        remera.put("fence_low", 500);
        remera.put("fence_high", 28500);

        db.guardarCategoriaStats(stats);
        Map<String, CategoriaStats> cargado = db.cargarCategoriaStats();

        assertThat(cargado).containsKey("Remera");
        CategoriaStats r = cargado.get("Remera");
        assertThat(r.n()).isEqualTo(42);
        assertThat(r.mean()).isEqualTo(15000);
        assertThat(r.median()).isEqualTo(14500);
        assertThat(r.mode()).isEqualTo(12000);
        assertThat(r.std()).isEqualTo(3200);
        assertThat(r.cv()).isEqualTo(21.3);
        assertThat(r.q1()).isEqualTo(11000);
        assertThat(r.q3()).isEqualTo(18000);
        assertThat(r.iqr()).isEqualTo(7000);
        assertThat(r.mad()).isEqualTo(2500);
        assertThat(r.fenceLow()).isEqualTo(500);
        assertThat(r.fenceHigh()).isEqualTo(28500);
    }

    @Test
    @DisplayName("una clave no canónica se descarta SOLA, sin tirar abajo el resto del batch")
    void claveNoCanonicaSeDescartaSinRollbackDelBatch() {
        ObjectNode stats = mapper.createObjectNode();
        ObjectNode remera = stats.putObject("Remera");
        camposMinimos(remera);
        ObjectNode inventada = stats.putObject("CategoriaQueNoExiste");
        camposMinimos(inventada);

        db.guardarCategoriaStats(stats);
        Map<String, CategoriaStats> cargado = db.cargarCategoriaStats();

        assertThat(cargado).containsKey("Remera");
        assertThat(cargado).doesNotContainKey("CategoriaQueNoExiste");
    }

    @Test
    @DisplayName("re-guardar la misma categoria hace upsert, no duplica filas")
    void reguardarLaMismaCategoriaActualiza() {
        ObjectNode stats1 = mapper.createObjectNode();
        ObjectNode remera1 = stats1.putObject("Remera");
        camposMinimos(remera1);
        db.guardarCategoriaStats(stats1);

        ObjectNode stats2 = mapper.createObjectNode();
        ObjectNode remera2 = stats2.putObject("Remera");
        camposMinimos(remera2);
        remera2.put("n", 99);
        db.guardarCategoriaStats(stats2);

        Map<String, CategoriaStats> cargado = db.cargarCategoriaStats();
        assertThat(cargado).hasSize(1);
        assertThat(cargado.get("Remera").n()).isEqualTo(99);
    }

    private void camposMinimos(ObjectNode n) {
        n.put("n", 10);
        n.put("mean", 1000);
        n.put("median", 1000);
        n.put("mode", 1000);
        n.put("std", 100);
        n.put("cv", 10.0);
        n.put("q1", 900);
        n.put("q3", 1100);
        n.put("iqr", 200);
        n.put("mad", 80);
        n.put("fence_low", 700);
        n.put("fence_high", 1300);
    }
}
