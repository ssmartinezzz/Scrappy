package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.4 (V8).
 *
 * <p>V5 retyped only the two timestamps {@code sp_upsert_run} writes, because
 * Postgres has no partial function redefinition and carrying the whole body
 * forward is the expensive part. The remaining {@code *_at} columns cost no
 * function re-copy, so they ride here — as real {@code TIMESTAMPTZ}, not TEXT
 * that happens to look like a date.</p>
 *
 * <p>The sweep is the load-bearing assertion: an explicit list only proves the
 * columns someone remembered to list. A future migration that adds one more
 * TEXT {@code *_at} column fails here instead of quietly rebuilding the thing
 * this slice removed.</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("Every *_at column is TIMESTAMPTZ — V8")
@DisplayName("V8 migration — no timestamp column is TEXT anymore")
class TimestamptzColumnTypeContractTest extends PostgresTestBase {

    private static final Map<String, List<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("productos", List.of("touched_at", "created_at", "bloqueado_at"));
        EXPECTED.put("image_embeddings", List.of("computed_at"));
        EXPECTED.put("ml_output", List.of("created_at"));
        EXPECTED.put("sitios_dinamicos", List.of("created_at"));
        EXPECTED.put("categoria_stats", List.of("updated_at"));
        EXPECTED.put("favoritos", List.of("added_at", "last_checked_at"));
        EXPECTED.put("outfit_feedback", List.of("created_at"));
        EXPECTED.put("outfit_feedback_item", List.of("created_at"));
        EXPECTED.put("categoria_dismiss", List.of("created_at"));
        EXPECTED.put("financiacion_presets", List.of("created_at"));
        EXPECTED.put("saved_outfits", List.of("created_at"));
        EXPECTED.put("cron_jobs", List.of("created_at", "updated_at", "last_run_at", "next_run_at"));
        EXPECTED.put("cron_executions", List.of("started_at", "finished_at"));
        EXPECTED.put("agent_reclassify_audit", List.of("applied_at"));
    }

    @Test
    @DisplayName("Every declared timestamp column is timestamp with time zone")
    void everyDeclaredTimestampColumnIsTimestamptz() throws Exception {
        Map<String, String> tipos = columnTypes();
        List<String> equivocadas = new ArrayList<>();
        for (Map.Entry<String, List<String>> tabla : EXPECTED.entrySet()) {
            for (String columna : tabla.getValue()) {
                String clave = tabla.getKey() + "." + columna;
                String tipo = tipos.get(clave);
                if (!"timestamp with time zone".equals(tipo)) {
                    equivocadas.add(clave + " is " + tipo);
                }
            }
        }
        assertThat(equivocadas).as("columns still not TIMESTAMPTZ").isEmpty();
    }

    @Test
    @DisplayName("No *_at column anywhere in the schema is still TEXT")
    void noTimestampColumnIsStillText() throws Exception {
        List<String> sobrevivientes = new ArrayList<>();
        for (Map.Entry<String, String> entry : columnTypes().entrySet()) {
            String columna = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
            if (columna.endsWith("_at") && !"timestamp with time zone".equals(entry.getValue())) {
                sobrevivientes.add(entry.getKey() + " is " + entry.getValue());
            }
        }
        assertThat(sobrevivientes)
                .as("a *_at column that is not TIMESTAMPTZ is a timestamp stored as prose")
                .isEmpty();
    }

    private Map<String, String> columnTypes() throws Exception {
        Map<String, String> tipos = new LinkedHashMap<>();
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name, column_name, data_type FROM information_schema.columns "
                             + "WHERE table_schema='public' AND table_name <> 'flyway_schema_history' "
                             + "ORDER BY table_name, ordinal_position");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tipos.put(rs.getString(1) + "." + rs.getString(2), rs.getString(3));
            }
        }
        return tipos;
    }
}
