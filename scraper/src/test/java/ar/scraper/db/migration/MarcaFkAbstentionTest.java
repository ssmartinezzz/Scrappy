package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * close-1nf-and-3nf-foundation extension, Phase 2 (V21, design E4).
 *
 * <p>Real Postgres, {@code TEST-3}. Writes go straight to {@code productos}
 * via JDBC, not through {@code sp_upsert_run} — that stored procedure still
 * writes {@code COALESCE(r->>'marca', '')} (empty string, not {@code NULL})
 * until {@code V23} lands the {@code nullif} substitution (design E6), and
 * {@code V23} is scheduled AFTER this phase. Migrations always apply
 * together in one Flyway run before any scrape can reach the procedure, so
 * there is no live window where this matters in production — but it does
 * mean this test proves the FK/{@code NULL} semantics at the schema layer,
 * independent of the write path; {@code SpUpsertRunRoundTripTest} (Phase 4)
 * covers the same abstention through the real upsert once the fix lands.</p>
 */
@Epic("Persistence")
@Feature("Brand")
@Story("marca FK never rejects abstention")
@DisplayName("V21 — marca FK abstention (real Postgres)")
class MarcaFkAbstentionTest extends PostgresTestBase {

    @Test
    @DisplayName("marca=NULL nunca es rechazado por la FK — es la abstención")
    void nullMarcaIsNeverRejected() {
        assertThatCode(() -> insertarConMarca("https://marca-fk.test/null", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una marca curada real (sembrada por V21) satisface la FK")
    void aCuratedBrandSatisfiesTheFk() {
        assertThatCode(() -> insertarConMarca("https://marca-fk.test/nike", "Nike"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una marca inexistente SÍ es rechazada — la FK enforcea de verdad")
    void aNonexistentBrandIsRejected() {
        assertThatThrownBy(() -> insertarConMarca("https://marca-fk.test/inventada", "MarcaQueNoExiste"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("NULL en la DB sale como \"\" del lado Java (ProductRowMapper ya era null-safe)")
    void nullComesBackAsEmptyStringThroughTheJavaMapper() throws Exception {
        insertarConMarca("https://marca-fk.test/lectura", null);

        DatabaseService db = new DatabaseService(dataSource());
        Product leido = db.cargarProductos().stream()
                .filter(p -> "https://marca-fk.test/lectura".equals(p.url()))
                .findFirst()
                .orElseThrow();

        assertThat(leido.marca()).isEqualTo("");
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private void insertarConMarca(String url, String marca) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio, marca) VALUES (?, 'Sitio', 'Producto', 1000, ?)")) {
            ps.setString(1, url);
            ps.setString(2, marca);
            ps.executeUpdate();
        }
    }
}
