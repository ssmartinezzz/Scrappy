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
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * normalize-db-schema-fks-1nf, slice A.3 (V6).
 *
 * <p>Covers spec "db-column-domains" &gt; "CHECK domain admissibility" —
 * three CHECK constraints on {@code productos.genero}/{@code rubro}/
 * {@code ml_segment} enumerating the exact live-verified domain (obs #839).
 * NULL is admitted on all three: none of the three columns is
 * {@code NOT NULL} (V1__baseline.sql:38,44,46). Blank is admitted on
 * {@code genero} only — {@link ar.scraper.aggregator.normalize.GenderResolver}'s
 * abstention sentinel, CODE-5 forbids making "no opinion" illegal. Real
 * Postgres CHECK enforcement via {@link PostgresTestBase}, no mocks.</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("V6 — genero/rubro/ml_segment CHECK domain constraints")
@DisplayName("V6 migration — value domain CHECK constraints")
class CheckDomainTest extends PostgresTestBase {

    private static final List<String> CHECK_CONSTRAINT_NAMES = List.of(
            "chk_productos_genero_domain",
            "chk_productos_rubro_domain",
            "chk_productos_ml_segment_domain");

    @Test
    @DisplayName("all three CHECK constraints exist on productos")
    void allThreeCheckConstraintsExist() throws Exception {
        for (String name : CHECK_CONSTRAINT_NAMES) {
            assertThat(constraintExists(name)).as("constraint %s exists", name).isTrue();
        }
    }

    @Test
    @DisplayName("genero CHECK accepts every domain value, blank, and NULL")
    void generoAcceptsDomainBlankAndNull() {
        int i = 0;
        for (String genero : List.of("hombre", "mujer", "unisex", "infantil", "")) {
            String url = "https://check-domain.test/genero-" + (i++);
            assertThatCode(() -> insertarConGenero(url, genero)).doesNotThrowAnyException();
        }
        assertThatCode(() -> insertarConGenero("https://check-domain.test/genero-null", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("genero CHECK rejects an out-of-domain / unnormalised value")
    void generoRejectsOutOfDomainValue() {
        assertThatThrownBy(() -> insertarConGenero("https://check-domain.test/genero-bad", "Mujer"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("rubro CHECK accepts every domain value and NULL")
    void rubroAcceptsDomainValuesAndNull() {
        int i = 0;
        for (String rubro : List.of("indumentaria", "tecnologia", "suplementos")) {
            String url = "https://check-domain.test/rubro-" + (i++);
            assertThatCode(() -> insertarConRubro(url, rubro)).doesNotThrowAnyException();
        }
        assertThatCode(() -> insertarConRubro("https://check-domain.test/rubro-null", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rubro CHECK rejects an out-of-domain value")
    void rubroRejectsOutOfDomainValue() {
        assertThatThrownBy(() -> insertarConRubro("https://check-domain.test/rubro-bad", "ropa"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("ml_segment CHECK accepts every domain value and NULL")
    void mlSegmentAcceptsDomainValuesAndNull() {
        int i = 0;
        for (String segment : List.of("budget", "standard", "premium", "luxury")) {
            String url = "https://check-domain.test/segment-" + (i++);
            assertThatCode(() -> insertarConMlSegment(url, segment)).doesNotThrowAnyException();
        }
        assertThatCode(() -> insertarConMlSegment("https://check-domain.test/segment-null", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ml_segment CHECK rejects an out-of-domain value")
    void mlSegmentRejectsOutOfDomainValue() {
        assertThatThrownBy(() -> insertarConMlSegment("https://check-domain.test/segment-bad", "ultra"))
                .isInstanceOf(SQLException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void insertarConGenero(String url, String genero) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio, genero) VALUES (?, 'Sitio', 'Producto', 1000, ?)")) {
            ps.setString(1, url);
            setNullableString(ps, 2, genero);
            ps.executeUpdate();
        }
    }

    private void insertarConRubro(String url, String rubro) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES (?, 'Sitio', 'Producto', 1000, ?)")) {
            ps.setString(1, url);
            setNullableString(ps, 2, rubro);
            ps.executeUpdate();
        }
    }

    private void insertarConMlSegment(String url, String segment) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO productos (url, sitio, nombre, precio, ml_segment) VALUES (?, 'Sitio', 'Producto', 1000, ?)")) {
            ps.setString(1, url);
            setNullableString(ps, 2, segment);
            ps.executeUpdate();
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private boolean constraintExists(String name) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_constraint WHERE conrelid = 'productos'::regclass "
                             + "AND contype = 'c' AND conname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
