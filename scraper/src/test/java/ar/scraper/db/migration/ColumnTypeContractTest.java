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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.2 (V5), task 2.1.
 *
 * <p>Covers spec "db-column-domains" — the INTEGER-boolean columns retype to
 * native {@code BOOLEAN}, and {@code precio_historico.fecha}/
 * {@code precios_externos.fecha} retype to {@code DATE}. {@code productos.
 * touched_at}/{@code created_at} ride in this same slice (design D1) because
 * {@code sp_upsert_run}/{@code sp_soft_delete_ausentes} write them and
 * Postgres has no partial function redefinition — the other ~20 TEXT
 * {@code *_at} columns are untouched here and ride in their own slice (A.4).
 *
 * <p>9 physical columns retype to {@code boolean} for 8 distinct column
 * NAMES — {@code activo} exists on both {@code productos} and
 * {@code financiacion_presets}, and both instances retype (design D2 —
 * {@code PresetRepository.java:68,87,99,169,171} are explicitly in scope).</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("V5 — boolean and date/timestamp column types")
@DisplayName("V5 migration — boolean and date/timestamp column types")
class ColumnTypeContractTest extends PostgresTestBase {

    private record ColumnType(String table, String column, String expectedDataType) {
    }

    private static final List<ColumnType> EXPECTED = List.of(
            new ColumnType("productos", "activo", "boolean"),
            new ColumnType("productos", "gymrat", "boolean"),
            new ColumnType("productos", "marca_premium", "boolean"),
            new ColumnType("productos", "ml_oferta", "boolean"),
            new ColumnType("cron_jobs", "force_retrain", "boolean"),
            new ColumnType("cron_jobs", "use_gpu", "boolean"),
            new ColumnType("cron_jobs", "enabled", "boolean"),
            new ColumnType("outfit_feedback_item", "liked", "boolean"),
            new ColumnType("financiacion_presets", "activo", "boolean"),
            new ColumnType("precio_historico", "fecha", "date"),
            new ColumnType("precios_externos", "fecha", "date"),
            new ColumnType("productos", "touched_at", "timestamp with time zone"),
            new ColumnType("productos", "created_at", "timestamp with time zone")
    );

    @Test
    @DisplayName("every retyped column reports the expected information_schema data_type")
    void everyRetypedColumnHasExpectedDataType() throws Exception {
        for (ColumnType expected : EXPECTED) {
            String actual = readDataType(expected.table(), expected.column());
            assertThat(actual)
                    .as("%s.%s data_type", expected.table(), expected.column())
                    .isEqualTo(expected.expectedDataType());
        }
    }

    private String readDataType(String table, String column) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s exists", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }
}
