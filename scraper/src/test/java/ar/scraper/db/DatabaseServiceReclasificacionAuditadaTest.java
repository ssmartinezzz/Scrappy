package ar.scraper.db;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED→GREEN coverage for {@link DatabaseService#aplicarReclasificacionAuditada}
 * (agent-chat-finetune WU1) — the truthful, audited write path behind
 * {@code POST /api/agent/apply}. Mirrors {@link DatabaseServiceCantidadUnidadesTest}'s
 * structure: a real Postgres connection via {@link PostgresTestBase}, no mocks,
 * so the atomic UPDATE + audit INSERT transaction is exercised for real.
 */
@Epic("Persistence")
@Feature("LLM Catalog Agent")
@Story("Audited reclassification write")
@DisplayName("DatabaseService — aplicarReclasificacionAuditada")
class DatabaseServiceReclasificacionAuditadaTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, String categoria, String marca, String genero, List<String> talles) {
        return new Product("Sitio", "Producto", 15000.0, null, url, "http://img.example/x.jpg",
                categoria, genero, talles, Product.MlScore.EMPTY, marca,
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private int contarFilasAudit(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM agent_reclassify_audit WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("success: UPDATE applied + exactly one audit row with old and new values")
    void successWritesUpdateAndAuditRow() throws Exception {
        Product previo = producto("https://site.com/p1", "Remeras", "Nike", "hombre", List.of("M", "L"));
        db.upsertProductos(List.of(previo));

        boolean applied = db.aplicarReclasificacionAuditada(
                "https://site.com/p1", "Buzo", "Adidas", "mujer", List.of("S"), "urbano", previo);

        assertThat(applied).isTrue();

        Optional<Product> actualizado = db.obtenerProducto("https://site.com/p1");
        assertThat(actualizado).isPresent();
        assertThat(actualizado.get().categoria()).isEqualTo("Buzo");
        assertThat(actualizado.get().marca()).isEqualTo("Adidas");
        assertThat(actualizado.get().genero()).isEqualTo("mujer");
        assertThat(actualizado.get().subCategoria()).isEqualTo("urbano");

        assertThat(contarFilasAudit("https://site.com/p1")).isEqualTo(1);

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT categoria_antes, categoria_despues, marca_antes, marca_despues, " +
                     "genero_antes, genero_despues, sub_categoria_antes, sub_categoria_despues " +
                     "FROM agent_reclassify_audit WHERE url=?");) {
            ps.setString(1, "https://site.com/p1");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("categoria_antes")).isEqualTo("Remeras");
                assertThat(rs.getString("categoria_despues")).isEqualTo("Buzo");
                assertThat(rs.getString("marca_antes")).isEqualTo("Nike");
                assertThat(rs.getString("marca_despues")).isEqualTo("Adidas");
                assertThat(rs.getString("genero_antes")).isEqualTo("hombre");
                assertThat(rs.getString("genero_despues")).isEqualTo("mujer");
                assertThat(rs.getString("sub_categoria_antes")).isEqualTo("");
                assertThat(rs.getString("sub_categoria_despues")).isEqualTo("urbano");
            }
        }
    }

    @Test
    @DisplayName("zero-row UPDATE (nonexistent url) → false, zero audit rows, no partial write")
    void nonexistentUrlReturnsFalseWithNoAuditRow() throws Exception {
        Product previoInexistente = producto("https://site.com/no-existe", "Remeras", "Nike", "hombre", List.of());

        boolean applied = db.aplicarReclasificacionAuditada(
                "https://site.com/no-existe", "Buzo", "Adidas", "mujer", List.of(), "urbano", previoInexistente);

        assertThat(applied).isFalse();
        assertThat(contarFilasAudit("https://site.com/no-existe")).isEqualTo(0);
    }

    @Test
    @DisplayName("audit INSERT failure (schema mutation) rolls back the UPDATE — no partial write")
    void auditInsertFailureRollsBackTheUpdate() throws Exception {
        Product previo = producto("https://site.com/p2", "Remeras", "Nike", "hombre", List.of("M"));
        db.upsertProductos(List.of(previo));

        // Fault injection: mutate the audit table's schema so the INSERT in
        // aplicarReclasificacionAuditada fails mid-transaction. A real
        // transaction rollback, not a mocked connection, is the point here —
        // the finally block restores the column so later tests (which share
        // the same DataSource via PostgresTestBase) never see a broken table.
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE agent_reclassify_audit DROP COLUMN categoria_despues");
        }
        try {
            boolean applied = db.aplicarReclasificacionAuditada(
                    "https://site.com/p2", "Buzo", "Adidas", "mujer", List.of("S"), "urbano", previo);

            assertThat(applied).isFalse();

            Optional<Product> sinCambios = db.obtenerProducto("https://site.com/p2");
            assertThat(sinCambios).isPresent();
            assertThat(sinCambios.get().categoria()).isEqualTo("Remeras"); // UPDATE was rolled back
            assertThat(sinCambios.get().marca()).isEqualTo("Nike");
        } finally {
            try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
                st.execute("ALTER TABLE agent_reclassify_audit ADD COLUMN categoria_despues TEXT");
            }
        }
    }
}
