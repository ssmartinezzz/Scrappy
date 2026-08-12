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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 2 (tasks 2.4/2.5).
 *
 * <p>Every column of {@code productos} MUST fall into exactly one declared
 * bucket describing how {@code sp_upsert_run} treats it on a re-upsert:
 * unconditionally overwritten, fill-only (blank incoming value preserves the
 * prior value, RELY-001), locked (Decision D3 — frozen once
 * {@code bloqueado_por} is set), or never touched by the upsert at all (the
 * conflict key, or a column only set at INSERT time). A future column added
 * to {@code productos} without updating one of these buckets fails this fast
 * test instead of silently defaulting to "overwritten".</p>
 */
@Epic("Persistence")
@Feature("Manual classification lock")
@Story("Every productos column is accounted for by a declared upsert bucket")
@DisplayName("sp_upsert_run — every productos column falls into exactly one bucket")
class SpUpsertRunColumnCoverageTest extends PostgresTestBase {

    // talles/ml_badge dejaron de ser columnas de productos en V7 — viven en
    // producto_talle/producto_badge, que sp_upsert_run reescribe entero por
    // producto (DELETE + INSERT). Declararlas acá sería declarar columnas que
    // ya no existen.
    private static final Set<String> OVERWRITTEN = Set.of(
            "sitio", "nombre", "precio", "precio_orig", "imagen_url",
            "ml_score", "ml_oferta", "ml_tendencia", "ml_segment", "ml_zscore",
            "gymrat", "marca_premium", "cantidad_unidades", "activo", "touched_at");

    private static final Set<String> FILL_ONLY = Set.of(
            "fit", "estampado", "escote", "color_dominante");

    private static final Set<String> LOCKED = Set.of(
            "categoria", "sub_categoria", "marca", "genero", "rubro");

    private static final Set<String> LOCKED_METADATA = Set.of(
            "bloqueado_por", "bloqueado_at");

    private static final Set<String> NEVER_IN_UPSERT = Set.of(
            "url", "created_at");

    @Test
    void everyProductosColumnFallsIntoExactlyOneDeclaredBucket() throws Exception {
        List<String> columns = readProductosColumns();
        assertThat(columns).as("productos table has columns").isNotEmpty();

        List<Set<String>> buckets = List.of(OVERWRITTEN, FILL_ONLY, LOCKED, LOCKED_METADATA, NEVER_IN_UPSERT);
        Set<String> allDeclared = new HashSet<>();
        for (Set<String> bucket : buckets) {
            for (String col : bucket) {
                assertThat(allDeclared.add(col))
                        .as("column '%s' declared in more than one bucket", col)
                        .isTrue();
            }
        }

        List<String> uncovered = new ArrayList<>();
        for (String column : columns) {
            if (!allDeclared.contains(column)) {
                uncovered.add(column);
            }
        }

        assertThat(uncovered)
                .as("every productos column must be classified into a bucket, or sp_upsert_run's "
                        + "treatment of it is undocumented")
                .isEmpty();
    }

    private List<String> readProductosColumns() throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = 'productos' "
                             + "ORDER BY ordinal_position")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }
}
