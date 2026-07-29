package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * manual-classification-lock — review fix F4.
 *
 * <p>{@code chk_productos_bloqueo} (V3) claims that {@code bloqueado_por}/
 * {@code bloqueado_at} are either both NULL (unlocked) or both populated with
 * a non-blank actor (locked) — never a mix. The original expression did not
 * deliver that: for {@code bloqueado_por IS NULL AND bloqueado_at IS NOT NULL},
 * the first OR-branch evaluates to FALSE and the second ({@code bloqueado_por
 * <> ''} against a NULL {@code bloqueado_por}) evaluates to NULL, so the whole
 * CHECK expression is NULL — and PostgreSQL accepts a CHECK that evaluates to
 * NULL (only an explicit FALSE is rejected). The half-locked state the
 * comment says is prevented was silently allowed. V3 has never run against a
 * persistent database (only ephemeral Testcontainers instances), so it is
 * edited in place rather than superseded by a V4.</p>
 */
@Epic("Persistence")
@Feature("Manual classification lock")
@Story("chk_productos_bloqueo rejects the half-locked state, not just full lock/unlock")
@DisplayName("V3 migration — chk_productos_bloqueo rejects bloqueado_at without bloqueado_por")
class V3ManualClassificationLockConstraintTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url) {
        return new Product("Sitio", "Producto", 1000.0, null, url, "", "Remeras", "hombre", List.of());
    }

    @Test
    @DisplayName("bloqueado_at set while bloqueado_por stays NULL is rejected by the CHECK constraint")
    void halfLockedStateWithNullActorIsRejected() throws Exception {
        String url = "https://site.com/half-locked-null-actor";
        db.upsertProductos(List.of(producto(url)));

        try (Connection c = dataSource().getConnection()) {
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE productos SET bloqueado_at=? WHERE url=?")) {
                    ps.setString(1, "2026-07-28 12:00:00");
                    ps.setString(2, url);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("bloqueado_por set blank while bloqueado_at is populated is still rejected")
    void halfLockedStateWithBlankActorIsRejected() throws Exception {
        String url = "https://site.com/half-locked-blank-actor";
        db.upsertProductos(List.of(producto(url)));

        try (Connection c = dataSource().getConnection()) {
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE productos SET bloqueado_por=?, bloqueado_at=? WHERE url=?")) {
                    ps.setString(1, "");
                    ps.setString(2, "2026-07-28 12:00:00");
                    ps.setString(3, url);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("fully unlocked (both NULL) and fully locked (both populated) states remain valid")
    void fullyLockedAndFullyUnlockedStatesRemainValid() throws Exception {
        String url = "https://site.com/full-states-still-valid";
        db.upsertProductos(List.of(producto(url)));

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET bloqueado_por=?, bloqueado_at=? WHERE url=?")) {
            ps.setString(1, "local");
            ps.setString(2, "2026-07-28 12:00:00");
            ps.setString(3, url);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}
