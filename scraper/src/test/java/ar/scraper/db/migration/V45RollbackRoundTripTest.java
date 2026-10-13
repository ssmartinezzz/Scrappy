package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Persistence")
@Feature("Site registry")
@Story("V45 rollback narrows the rubro domains back, seed-first")
@DisplayName("V45 migration — the documented rollback actually runs")
class V45RollbackRoundTripTest extends PostgresTestBase {

    private static final List<String> SITIOS = List.of("mrplacadeco", "vayzenhome", "ambiancedeco");
    private static final List<String> CATEGORIAS = List.of(
            "Placa Decorativa", "Vinilo Decorativo", "Piso", "Césped Sintético", "Adhesivo",
            "Moldura", "Planta Artificial", "Almohadón", "Funda", "Manta", "Mantel", "Alfombra",
            "Respaldo");
    private static final String URL = "https://mrplacadeco.test/productos/placa-marmol";

    @Test
    @DisplayName("Without products, the rollback removes seed and categories and narrows both CHECKs")
    void rollbackRemovesSeedThenNarrowsTheChecks() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(domainOf(st, "productos", "chk_productos_rubro_domain"))
                        .contains("construccion", "hogar");
                assertThat(domainOf(st, "sitio", "sitio_rubro_forzado_check"))
                        .contains("construccion", "hogar");
                assertThat(sitioKeys(st)).containsAll(SITIOS);
                assertThat(categorias(st)).containsAll(CATEGORIAS);

                st.execute(DocumentedRollback.sqlFor("V45"));

                assertThat(domainOf(st, "productos", "chk_productos_rubro_domain"))
                        .containsExactlyInAnyOrder("indumentaria", "tecnologia", "suplementos", "oficina");
                assertThat(domainOf(st, "sitio", "sitio_rubro_forzado_check"))
                        .containsExactlyInAnyOrder("tecnologia", "suplementos", "oficina");
                assertThat(sitioKeys(st)).doesNotContainAnyElementsOf(SITIOS);
                assertThat(categorias(st)).doesNotContainAnyElementsOf(CATEGORIAS);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("With products of a seeded site alive, the rollback no longer applies and fails loudly")
    void rollbackIsNoLongerAvailableOnceASiteHasProducts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                        VALUES ('%s', 'Mrplacadeco', 'Placa Mármol Autoadhesiva', 9990,
                                'construccion', 'Placa Decorativa')
                        """.formatted(URL));

                assertThatThrownBy(() -> st.execute(DocumentedRollback.sqlFor("V45")))
                        .as("narrowing the domain under a live 'construccion' site must not pass silently")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("sitio_rubro_forzado_check");
            } finally {
                c.rollback();
            }
        }
    }

    private static List<String> domainOf(Statement st, String tabla, String constraint) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = '" + tabla + "'::regclass AND conname = '" + constraint + "'")) {
            assertThat(rs.next()).as("%s existe en %s", constraint, tabla).isTrue();
            String def = rs.getString(1);
            List<String> values = new ArrayList<>();
            Matcher m = Pattern.compile("'([a-z0-9]+)'").matcher(def);
            while (m.find()) values.add(m.group(1));
            return values;
        }
    }

    private static List<String> sitioKeys(Statement st) throws Exception {
        return column(st, "SELECT sitio_key FROM sitio");
    }

    private static List<String> categorias(Statement st) throws Exception {
        return column(st, "SELECT nombre FROM categoria");
    }

    private static List<String> column(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }
}
