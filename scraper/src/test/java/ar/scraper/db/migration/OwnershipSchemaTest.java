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
 * user-accounts-and-roles, slice 1 — the ownership columns, and just as
 * importantly the tables that deliberately do NOT get one.
 *
 * <p>The column is nullable on purpose and that is the whole difficulty of this
 * migration: {@code V26} runs under Flyway during startup, while the bootstrap
 * admin is seeded by an {@code ApplicationRunner} afterwards. At migration time
 * there is no user row for the existing favourites to belong to, so the column
 * cannot be {@code NOT NULL} and the composite key cannot be a
 * {@code PRIMARY KEY} — a PK forbids NULLs. Adoption closes the gap later in
 * the same startup, in application code, so the byte-frozen migration never has
 * to name a user id.</p>
 *
 * <p><b>Two tables are excluded, for two different reasons</b>:
 * {@code saved_outfit_item} is a child of {@code saved_outfits} through
 * {@code ON DELETE CASCADE}, so it inherits its owner through the parent — a
 * second owner column could disagree with the first. And {@code outfit_feedback}
 * is not excluded so much as gone: {@code V15} dropped it outright. The task
 * list still described it as a live legacy table, which it stopped being
 * several migrations ago; this test pins the fact rather than the stale plan.</p>
 */
@Epic("Persistence")
@Feature("Data ownership")
@Story("V26 — nullable usuario_id on the four personal tables, and on nothing else")
@DisplayName("V26 migration — ownership columns")
class OwnershipSchemaTest extends PostgresTestBase {

    /** The four tables holding rows that belong to one person. */
    private static final List<String> TABLAS_CON_DUENO =
            List.of("favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss");

    // ── 1.5 / 1.6 · the column exists, is nullable, and cascades ─────────────

    @Test
    @DisplayName("each personal table gains a nullable usuario_id")
    void eachPersonalTableGainsANullableOwnerColumn() throws Exception {
        for (String tabla : TABLAS_CON_DUENO) {
            assertThat(tipoDeColumna(tabla, "usuario_id"))
                    .as("%s.usuario_id exists and is a UUID", tabla)
                    .isEqualTo("uuid");
            assertThat(esNullable(tabla, "usuario_id"))
                    .as("%s.usuario_id must be nullable: at migration time no user row exists yet", tabla)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("each usuario_id is a FK to usuario ON DELETE CASCADE")
    void eachOwnerColumnCascadesFromUsuario() throws Exception {
        for (String tabla : TABLAS_CON_DUENO) {
            assertThat(reglaDeBorradoDeLaFk(tabla, "usuario_id"))
                    .as("deleting a user must not leave their personal rows pointing at nobody (%s)", tabla)
                    .isEqualTo("CASCADE");
        }
    }

    @Test
    @DisplayName("favoritos' primary key is not yet the composite (usuario_id, url)")
    void favoritosPrimaryKeyIsNotYetComposite() throws Exception {
        assertThat(columnasDeLaPk("favoritos"))
                .as("a PRIMARY KEY forbids NULLs, and the owner must stay nullable until adoption runs — "
                        + "the composite ships as UNIQUE in this phase, not as the PK")
                .doesNotContain("usuario_id");
    }

    // ── 1.7 · the tables that must NOT gain a column ─────────────────────────

    @Test
    @DisplayName("saved_outfit_item gains no usuario_id — it inherits its owner through its parent")
    void savedOutfitItemGainsNoOwnerColumn() throws Exception {
        assertThat(tipoDeColumna("saved_outfit_item", "usuario_id"))
                .as("a second owner column on a CASCADE child could disagree with its parent")
                .isNull();
    }

    @Test
    @DisplayName("outfit_feedback does not exist at all — V15 dropped it")
    void outfitFeedbackIsGoneNotJustUnowned() throws Exception {
        assertThat(tablaExiste("outfit_feedback"))
                .as("the plan called it a live legacy table; V15__drop_legacy_outfit_feedback removed it")
                .isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code null} when the column does not exist. */
    private String tipoDeColumna(String tabla, String columna) throws Exception {
        return unaCadena(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? AND column_name=?",
                tabla, columna);
    }

    private boolean esNullable(String tabla, String columna) throws Exception {
        return "YES".equals(unaCadena(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? AND column_name=?",
                tabla, columna));
    }

    private String reglaDeBorradoDeLaFk(String tabla, String columna) throws Exception {
        return unaCadena(
                "SELECT rc.delete_rule "
                        + "FROM information_schema.key_column_usage kcu "
                        + "JOIN information_schema.referential_constraints rc "
                        + "  ON rc.constraint_name = kcu.constraint_name "
                        + " AND rc.constraint_schema = kcu.constraint_schema "
                        + "WHERE kcu.table_schema='public' AND kcu.table_name=? AND kcu.column_name=?",
                tabla, columna);
    }

    private boolean tablaExiste(String tabla) throws Exception {
        return unaCadena("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name=?", tabla, null) != null;
    }

    private List<String> columnasDeLaPk(String tabla) throws Exception {
        List<String> columnas = new java.util.ArrayList<>();
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kcu.column_name "
                             + "FROM information_schema.table_constraints tc "
                             + "JOIN information_schema.key_column_usage kcu "
                             + "  ON kcu.constraint_name = tc.constraint_name "
                             + " AND kcu.constraint_schema = tc.constraint_schema "
                             + "WHERE tc.table_schema='public' AND tc.table_name=? "
                             + "  AND tc.constraint_type='PRIMARY KEY'")) {
            ps.setString(1, tabla);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columnas.add(rs.getString(1));
                }
            }
        }
        return columnas;
    }

    private String unaCadena(String sql, String p1, String p2) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p1);
            if (p2 != null) {
                ps.setString(2, p2);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
