package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryGroups;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13 — `categoria` tiene integridad referencial.
 *
 * <p>El guard que más importa es {@link #laTablaYElCanonDeJavaNoPuedenDiverger()}:
 * el vocabulario vive en DOS lugares (la tabla y {@code CategoryGroups}) y si
 * se separan, el clasificador empieza a producir categorías que la FK rechaza
 * y los upserts fallan en producción, no acá.</p>
 */
@Epic("Persistence")
@Feature("Category classification")
@Story("categoria lookup table + FK — V13")
@DisplayName("V13 — categoria es un dominio con integridad referencial")
class CategoriaLookupTableTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("La tabla y el canon de Java no pueden divergir")
    void laTablaYElCanonDeJavaNoPuedenDiverger() throws Exception {
        List<String> enLaTabla = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre FROM categoria")) {
            while (rs.next()) enLaTabla.add(rs.getString(1));
        }

        assertThat(enLaTabla)
                .as("toda categoría que el clasificador puede devolver tiene que existir en la tabla")
                .containsExactlyInAnyOrderElementsOf(CategoryGroups.canonicalCategories());
    }

    @Test
    @DisplayName("Un producto con una categoría inventada no entra")
    void categoriaInventadaEsRechazada() {
        assertThatThrownBy(() -> insertarCrudo("https://s.com/x", "Categoria Que No Existe"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_productos_categoria");
    }

    @Test
    @DisplayName("El write-path real sigue funcionando: lo que el clasificador produce, la FK lo acepta")
    void elWritePathRealSigueFuncionando() {
        db.upsertProductos(List.of(producto("https://s.com/ok", "Remera")));

        assertThat(db.cargarProductos()).hasSize(1);
    }

    @Test
    @DisplayName("Dar de alta una categoría es un INSERT, no una migración")
    void altaDeCategoriaEsUnInsert() throws Exception {
        // `categoria` es dato de REFERENCIA sembrado por la migración, no
        // estado de test: PostgresTestBase no la trunca (si lo hiciera, la FK
        // rechazaría todo en el test siguiente). Por eso este test limpia lo
        // que ensucia, en vez de esperar que se lo limpien.
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO categoria (nombre) VALUES ('Categoria Nueva')");
            try {
                insertarCrudo("https://s.com/nueva", "Categoria Nueva");
                assertThat(db.obtenerProducto("https://s.com/nueva")).isPresent();
            } finally {
                st.executeUpdate("DELETE FROM productos WHERE categoria = 'Categoria Nueva'");
                st.executeUpdate("DELETE FROM categoria WHERE nombre = 'Categoria Nueva'");
            }
        }
    }

    private void insertarCrudo(String url, String categoria) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria) VALUES ('"
                    + url + "', 'Sitio', 'Producto', 1000, '" + categoria + "')");
        }
    }

    private Product producto(String url, String categoria) {
        return new Product("Sitio", "Producto", 1000.0, null, url, "http://img.example/x.jpg",
                categoria, "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }
}
